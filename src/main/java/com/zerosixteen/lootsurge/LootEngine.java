package com.zerosixteen.lootsurge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Decides and spawns the bonus loot. Vanilla drops and XP are never touched: this only adds extra item entities
 * after the mob has already died, and only on the server.
 */
final class LootEngine {
	private static final int DEDUPE_LIMIT = 512;

	private final Logger log;
	private final Path configDir;
	private final Effects effects;
	private final UniquePowers uniques;

	private volatile Settings settings;
	private volatile Map<String, LootPool> byEntity = Map.of();
	private volatile LootPool fallbackPool;
	private volatile Map<String, LootEntry> uniqueEntries = Map.of();
	private volatile Activities activities;
	/** True while spawn() adds its own items, so the ENTITY_LOAD restore does not add a second pillar for them. */
	private boolean spawning;

	/** Remembers recently handled mobs so one death can never roll twice. Only touched on the server thread. */
	private final Set<UUID> handled = new LinkedHashSet<>();

	LootEngine(Logger log, Path configDir, Effects effects, UniquePowers uniques) {
		this.log = log;
		this.configDir = configDir;
		this.effects = effects;
		this.uniques = uniques;
	}

	void setActivities(Activities activities) {
		this.activities = activities;
	}

	/** (Re)reads config.json and pools/*.json. Needs a running server because enchantments live in its registries. */
	void reload(MinecraftServer server) {
		try {
			Settings s = Settings.load(configDir.resolve("config.json"), log);
			List<LootPool> pools = PoolLoader.loadAll(configDir.resolve("pools"), server.registryAccess(), log);

			Map<String, LootPool> map = new HashMap<>();
			LootPool fallback = null;
			for (LootPool pool : pools) {
				if (pool.fallback) {
					if (fallback == null) {
						fallback = pool;
					} else {
						log.warn("[LootSurge] Pool '{}' is also marked fallback; keeping '{}'.", pool.name, fallback.name);
					}
				}
				for (String entity : pool.entities) {
					LootPool previous = map.put(entity, pool);
					if (previous != null) {
						log.warn("[LootSurge] Entity {} is in both '{}' and '{}'; '{}' wins.", entity, previous.name,
								pool.name, pool.name);
					}
				}
			}
			// Uniques come from the mob pools and from uniques/*.json (activity drops, tools, armor).
			List<LootEntry> allUniques = new ArrayList<>();
			for (LootPool pool : pools) {
				for (LootEntry entry : pool.entries) {
					if (entry.uniqueId != null) {
						allUniques.add(entry);
					}
				}
			}
			for (LootEntry entry : PoolLoader.loadUniques(configDir.resolve("uniques"), server.registryAccess(), log)) {
				allUniques.add(entry);
			}
			Map<String, UniquePowers.Powers> uniqueMap = new HashMap<>();
			Map<String, LootEntry> entryMap = new java.util.TreeMap<>();
			for (LootEntry entry : allUniques) {
				if (entry.powers != null) {
					uniqueMap.put(entry.uniqueId, entry.powers);
				}
				entryMap.put(entry.uniqueId, entry);
			}
			uniques.setRegistry(uniqueMap);
			Activities act = this.activities;
			if (act != null) {
				act.reload(entryMap.values());
			}
			this.uniqueEntries = Map.copyOf(entryMap);
			this.settings = s;
			this.byEntity = Map.copyOf(map);
			this.fallbackPool = fallback;
			effects.clear();
			log.info("[LootSurge] Loaded {} pools {} (fallback: {}). Feature enabled: {}.", pools.size(),
					PoolLoader.summarize(pools), fallback == null ? "none" : fallback.name, s.enabled);
		} catch (RuntimeException e) {
			log.error("[LootSurge] Reload failed; keeping previous settings.", e);
		}
	}

	/** Fabric AFTER_DEATH callback. Runs once per death on the server thread. */
	void onDeath(LivingEntity entity, DamageSource source) {
		try {
			handleDeath(entity, source);
		} catch (RuntimeException e) {
			log.error("[LootSurge] Error while rolling bonus loot (vanilla drops are unaffected).", e);
		}
	}

	private void handleDeath(LivingEntity entity, DamageSource source) {
		Settings s = this.settings;
		if (s == null || !s.enabled) {
			return;
		}
		if (!(entity.level() instanceof ServerLevel level)) {
			return;
		}
		if (entity instanceof Player) {
			return;
		}

		Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		if (typeId == null) {
			return;
		}
		String key = typeId.toString();
		if (s.excludedEntities.contains(key)) {
			return;
		}
		if (s.skipBabyMobs && entity.isBaby()) {
			return;
		}

		ServerPlayer killer = null;
		Entity attacker = source == null ? null : source.getEntity();
		if (attacker instanceof ServerPlayer sp) {
			killer = sp;
		}
		if (s.requirePlayerKill && killer == null) {
			return;
		}

		LootPool pool = byEntity.get(key);
		if (pool == null) {
			LootPool fb = this.fallbackPool;
			if (fb != null && (!s.fallbackMonstersOnly || entity.getType().getCategory() == MobCategory.MONSTER)) {
				pool = fb;
			}
		}
		if (pool == null) {
			return;
		}

		// Duplicate-drop guard: ignore a mob we have already processed.
		if (!handled.add(entity.getUUID())) {
			return;
		}
		if (handled.size() > DEDUPE_LIMIT) {
			Iterator<UUID> it = handled.iterator();
			it.next();
			it.remove();
		}

		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		double chance = Math.min(1.0, pool.dropChance * s.globalChanceMultiplier);
		if (rnd.nextDouble() >= chance) {
			return;
		}

		Tier tier = rollTier(s, pool, rnd);
		String label = key + " killed by " + (killer == null ? "environment" : killer.getName().getString());
		dropAt(level, entity.getX(), entity.getY(0.5), entity.getZ(), entity.getY(), pool, tier, killer, s, rnd, label);
	}

	/**
	 * Rolls and spawns one bonus drop of the given tier (walking down to a lower tier if the pool has nothing for it),
	 * plays the feedback and announces it. Returns false if nothing dropped.
	 */
	private boolean dropAt(ServerLevel level, double x, double y, double z, double feetY, LootPool pool, Tier tier,
			ServerPlayer killer, Settings s, ThreadLocalRandom rnd, String label) {
		List<LootEntry> eligible = List.of();
		while (tier != null) {
			eligible = pool.entriesFor(tier);
			if (!eligible.isEmpty()) {
				break;
			}
			tier = tier.lower();
		}
		if (tier == null || eligible.isEmpty()) {
			return false;
		}
		TierSettings ts = s.tier(tier);

		int rolls = ts.minItems + rnd.nextInt(ts.maxItems - ts.minItems + 1);
		List<ItemStack> stacks = new ArrayList<>();
		for (int i = 0; i < rolls; i++) {
			LootEntry entry = pickWeighted(eligible, rnd);
			ItemStack stack = entry.create(rnd);
			if (!stack.isEmpty()) {
				stacks.add(stack);
			}
		}
		boolean ok = spawn(level, x, y, z, feetY, tier, stacks, killer, s, rnd);
		if (ok && s.debugLogging) {
			log.info("[LootSurge] {} -> tier {} x{} (pool {}).", label, tier.key(), stacks.size(), pool.name);
		}
		return ok;
	}

	/** Drops the stacks with the tier's label, sounds, particles, pillar and announcement. Shared by mob and activity drops. */
	boolean spawn(ServerLevel level, double x, double y, double z, double feetY, Tier tier, List<ItemStack> stacks,
			ServerPlayer killer, Settings s, ThreadLocalRandom rnd) {
		if (stacks.isEmpty()) {
			return false;
		}
		TierSettings ts = s.tier(tier);
		boolean visual = ts.itemLabel || ts.pillar.enabled();

		List<ItemEntity> spawned = new ArrayList<>();
		spawning = true;
		try {
			addAll(level, x, y, z, tier, stacks, visual, ts, rnd, spawned);
		} finally {
			spawning = false;
		}
		if (spawned.isEmpty()) {
			return false;
		}

		effects.play(level, x, y, z, feetY, ts, spawned, s.maxActivePillars);
		announce(level, killer, ts, stacks);
		return true;
	}

	private void addAll(ServerLevel level, double x, double y, double z, Tier tier, List<ItemStack> stacks, boolean visual,
			TierSettings ts, ThreadLocalRandom rnd, List<ItemEntity> spawned) {
		for (ItemStack stack : stacks) {
			if (visual) {
				Rarity.tag(stack, tier);
			}
			ItemEntity drop = new ItemEntity(level, x, y, z, stack);
			drop.setDefaultPickUpDelay();
			drop.setDeltaMovement(new Vec3((rnd.nextDouble() - 0.5) * 0.25, 0.2 + rnd.nextDouble() * 0.1,
					(rnd.nextDouble() - 0.5) * 0.25));
			applyLabel(drop, ts);
			if (level.addFreshEntity(drop)) {
				spawned.add(drop);
			}
		}
	}

	private static void applyLabel(ItemEntity drop, TierSettings ts) {
		if (ts.itemLabel) {
			MutableComponent itemLabel = Component.literal("[" + ts.displayName + "] ").withStyle(ts.color);
			itemLabel.append(drop.getItem().getHoverName().copy().withStyle(ts.color));
			drop.setCustomName(itemLabel);
			drop.setCustomNameVisible(true);
		}
	}

	/**
	 * Fabric ENTITY_LOAD for every item entity: fires for fresh drops, items thrown by players, items from broken
	 * containers and items loaded with a chunk. If the stack remembers a tier, its label and pillar are restored.
	 */
	void onEntityLoad(Entity entity, ServerLevel level) {
		try {
			Settings s = this.settings;
			if (spawning || s == null || !s.enabled || !(entity instanceof ItemEntity drop)) {
				return;
			}
			Tier tier = Rarity.read(drop.getItem());
			if (tier == null) {
				return;
			}
			TierSettings ts = s.tier(tier);
			applyLabel(drop, ts);
			effects.addPillar(level, drop, ts.pillar, drop.getX(), drop.getY(), drop.getZ(), s.maxActivePillars);
		} catch (RuntimeException e) {
			log.error("[LootSurge] Could not restore rarity visuals (item is unaffected).", e);
		}
	}

	Settings settings() {
		return settings;
	}

	// ------------------------------------------------------------------ used by commands, the creative tab and the options screen

	/** Unique id -> the entry that creates it. */
	Map<String, LootEntry> uniqueEntries() {
		return uniqueEntries;
	}

	/** Spawns a test drop of a tier at the player's feet, using the first pool that has items for that tier. */
	String testDrop(ServerPlayer player, Tier tier) {
		Settings s = this.settings;
		if (s == null || !(player.level() instanceof ServerLevel level)) {
			return "Loot Surge is not ready yet.";
		}
		List<LootPool> candidates = new ArrayList<>();
		if (fallbackPool != null) {
			candidates.add(fallbackPool);
		}
		candidates.addAll(new LinkedHashSet<>(byEntity.values()));
		for (LootPool pool : candidates) {
			if (!pool.entriesFor(tier).isEmpty()) {
				boolean ok = dropAt(level, player.getX(), player.getY(0.5), player.getZ(), player.getY(), pool, tier,
						player, s, ThreadLocalRandom.current(), "test drop for " + player.getName().getString());
				return ok ? null : "Nothing dropped.";
			}
		}
		return "No pool has items for tier " + tier.key() + ".";
	}

	String forceActivityRoll(ServerPlayer player, String activity, String material) {
		Activities act = this.activities;
		return act == null ? "Activity drops are not loaded." : act.forceRoll(player, activity, material);
	}

	String pityText(ServerPlayer player) {
		Activities act = this.activities;
		if (act == null || !act.pityEnabled()) {
			return "The pity system is disabled (set pity.enabled in uniques/activities.json).";
		}
		Map<String, Integer> m = act.pityOf(player.getUUID());
		return m.isEmpty() ? "No dry streaks yet." : "Dry streaks (eligible rolls since your last Unique): " + new java.util.TreeMap<>(m);
	}

	String summary() {
		Settings s = this.settings;
		return s == null ? "not loaded" : (s.enabled ? "enabled" : "DISABLED") + ", " + byEntity.size()
				+ " mobs covered, " + uniqueEntries.size() + " Uniques loaded";
	}

	private static Tier rollTier(Settings s, LootPool pool, ThreadLocalRandom rnd) {
		Tier[] all = Tier.values();
		double[] weights = new double[all.length];
		double total = 0.0;
		for (int i = 0; i < all.length; i++) {
			weights[i] = s.tier(all[i]).weight * pool.tierMultiplier(all[i]);
			if (all[i] == Tier.UNIQUE && pool.entriesFor(Tier.UNIQUE).isEmpty()) {
				weights[i] = 0.0;
			}
			total += weights[i];
		}
		if (total <= 0.0) {
			return Tier.COMMON;
		}
		double roll = rnd.nextDouble() * total;
		for (int i = 0; i < all.length; i++) {
			roll -= weights[i];
			if (roll < 0.0 && weights[i] > 0.0) {
				return all[i];
			}
		}
		// Floating point safety net: highest tier that has any weight.
		for (int i = all.length - 1; i >= 0; i--) {
			if (weights[i] > 0.0) {
				return all[i];
			}
		}
		return Tier.COMMON;
	}

	private static LootEntry pickWeighted(List<LootEntry> entries, ThreadLocalRandom rnd) {
		double total = 0.0;
		for (LootEntry e : entries) {
			total += e.weight;
		}
		double roll = rnd.nextDouble() * total;
		for (LootEntry e : entries) {
			roll -= e.weight;
			if (roll < 0.0) {
				return e;
			}
		}
		return entries.get(entries.size() - 1);
	}

	private void announce(ServerLevel level, ServerPlayer killer, TierSettings ts, List<ItemStack> stacks) {
		if (ts.announce == TierSettings.Announce.NONE) {
			return;
		}
		MutableComponent items = Component.literal("");
		for (int i = 0; i < stacks.size(); i++) {
			if (i > 0) {
				items.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			}
			items.append(stacks.get(i).getDisplayName());
		}

		MutableComponent head = Component.literal("✦ " + ts.displayName.toUpperCase(java.util.Locale.ROOT) + " ✦ ")
				.withStyle(ts.color).withStyle(ChatFormatting.BOLD);

		switch (ts.announce) {
			case ACTIONBAR -> {
				if (killer != null) {
					killer.sendSystemMessage(head.copy().append(items), true);
				}
			}
			case CHAT -> {
				if (killer != null) {
					killer.sendSystemMessage(head.copy().append(items), false);
				}
			}
			case BROADCAST -> {
				String who = killer == null ? "Someone" : killer.getName().getString();
				MutableComponent msg = head.copy().append(Component.literal(who + " found ").withStyle(ChatFormatting.WHITE))
						.append(items);
				MinecraftServer server = level.getServer();
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					p.sendSystemMessage(msg, false);
				}
			}
			default -> {
			}
		}
	}
}
