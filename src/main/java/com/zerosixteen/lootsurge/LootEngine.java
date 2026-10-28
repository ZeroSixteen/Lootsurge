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

	private volatile Settings settings;
	private volatile Map<String, LootPool> byEntity = Map.of();
	private volatile LootPool fallbackPool;

	/** Remembers recently handled mobs so one death can never roll twice. Only touched on the server thread. */
	private final Set<UUID> handled = new LinkedHashSet<>();

	LootEngine(Logger log, Path configDir, Effects effects) {
		this.log = log;
		this.configDir = configDir;
		this.effects = effects;
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
		List<LootEntry> eligible = List.of();
		while (tier != null) {
			eligible = pool.entriesFor(tier);
			if (!eligible.isEmpty()) {
				break;
			}
			tier = tier.lower();
		}
		if (tier == null || eligible.isEmpty()) {
			return;
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
		if (stacks.isEmpty()) {
			return;
		}

		double x = entity.getX();
		double y = entity.getY(0.5);
		double z = entity.getZ();
		List<ItemEntity> spawned = new ArrayList<>();
		for (ItemStack stack : stacks) {
			ItemEntity drop = new ItemEntity(level, x, y, z, stack);
			drop.setDefaultPickUpDelay();
			drop.setDeltaMovement(new Vec3((rnd.nextDouble() - 0.5) * 0.25, 0.2 + rnd.nextDouble() * 0.1,
					(rnd.nextDouble() - 0.5) * 0.25));
			if (ts.itemLabel) {
				MutableComponent label = Component.literal("[" + ts.displayName + "] ").withStyle(ts.color);
				label.append(stack.getHoverName().copy().withStyle(ts.color));
				drop.setCustomName(label);
				drop.setCustomNameVisible(true);
			}
			if (level.addFreshEntity(drop)) {
				spawned.add(drop);
			}
		}
		if (spawned.isEmpty()) {
			return;
		}

		effects.play(level, x, y, z, entity.getY(), ts, spawned, s.maxActivePillars);
		announce(level, killer, ts, stacks);

		if (s.debugLogging) {
			log.info("[LootSurge] {} killed by {} -> tier {} x{} (pool {}).", key,
					killer == null ? "environment" : killer.getName().getString(), tier.key(), stacks.size(), pool.name);
		}
	}

	private static Tier rollTier(Settings s, LootPool pool, ThreadLocalRandom rnd) {
		Tier[] all = Tier.values();
		double[] weights = new double[all.length];
		double total = 0.0;
		for (int i = 0; i < all.length; i++) {
			weights[i] = s.tier(all[i]).weight * pool.tierMultiplier(all[i]);
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
