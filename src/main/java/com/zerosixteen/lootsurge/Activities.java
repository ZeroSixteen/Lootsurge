package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.FluidState;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Activity-based Unique drops. Doing an everyday activity (chopping, mining, digging, harvesting, shearing, fishing...)
 * rolls a tiny chance at a Unique from a pool that depends on the activity and the material. Everything here is
 * server-side and driven by Fabric events, stat counters, or short watches, so it needs no mixins.
 *
 * <p>Anti-spam: one roll per player per activity per cooldown (so tree-feller/vein-miner swings roll once), a block
 * position cannot roll twice within a few minutes (so place-and-break loops do not farm), and creative players never roll.
 */
final class Activities {
	private record Pick(LootEntry entry, double weight) {
	}

	private record Kind(String cat, String group, String material) {
	}

	private static final class Watch {
		final ServerLevel level;
		final UUID player;
		final UUID entity;
		final String cat;
		final String material;
		long expire;

		Watch(ServerLevel level, UUID player, UUID entity, String cat, String material, long expire) {
			this.level = level;
			this.player = player;
			this.entity = entity;
			this.cat = cat;
			this.material = material;
			this.expire = expire;
		}
	}

	private static final class BrushWatch {
		final ServerLevel level;
		final BlockPos pos;
		final UUID player;
		final String material;
		final long expire;

		BrushWatch(ServerLevel level, BlockPos pos, UUID player, String material, long expire) {
			this.level = level;
			this.pos = pos;
			this.player = player;
			this.material = material;
			this.expire = expire;
		}
	}

	private static final Map<String, Double> DEFAULT_CHANCE = Map.ofEntries(Map.entry("woodcutting", 0.0015),
			Map.entry("mining", 0.0012), Map.entry("excavation", 0.0010), Map.entry("nature", 0.0008),
			Map.entry("farming", 0.0020), Map.entry("shear", 0.010), Map.entry("milk", 0.010), Map.entry("honey", 0.020),
			Map.entry("breed", 0.020), Map.entry("egg", 0.002), Map.entry("tame", 0.030), Map.entry("horse", 0.040),
			Map.entry("fishing", 0.010), Map.entry("archaeology", 0.040), Map.entry("brewing", 0.015),
			Map.entry("water", 0.004), Map.entry("lava", 0.008));
	private static final Map<String, Integer> DEFAULT_COOLDOWN = Map.of("egg", 1200, "brewing", 400, "fishing", 100,
			"shear", 100, "milk", 100, "honey", 100, "breed", 100, "tame", 100, "horse", 100, "water", 200);
	private static final Set<String> STONE = Set.of("stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite",
			"cobblestone", "mossy_cobblestone", "cobbled_deepslate", "netherrack", "basalt", "smooth_basalt", "blackstone",
			"end_stone", "dripstone_block", "obsidian", "crying_obsidian", "sandstone", "red_sandstone", "terracotta",
			"magma_block", "prismarine");
	private static final Set<String> DIG = Set.of("dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium",
			"mud", "sand", "red_sand", "gravel", "clay", "snow", "snow_block", "powder_snow", "soul_sand", "soul_soil",
			"dirt_path", "farmland", "suspicious_sand", "suspicious_gravel", "moss_block", "pale_moss_block");
	private static final Set<String> CROPS = Set.of("nether_wart", "melon", "pumpkin", "sugar_cane", "cocoa",
			"sweet_berry_bush", "cave_vines", "cave_vines_plant");
	private static final Set<String> VINES = Set.of("vine", "weeping_vines", "weeping_vines_plant", "twisting_vines",
			"twisting_vines_plant", "glow_lichen");
	private static final Set<String> MUSHROOMS = Set.of("brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
			"brown_mushroom_block", "red_mushroom_block", "mushroom_stem");
	private static final Set<String> GRASS = Set.of("short_grass", "tall_grass", "fern", "large_fern", "seagrass",
			"tall_seagrass", "kelp", "kelp_plant", "dead_bush", "lily_pad", "bush", "firefly_bush", "leaf_litter",
			"hanging_roots", "big_dripleaf", "small_dripleaf", "spore_blossom", "azalea", "flowering_azalea", "cactus");
	private static final Set<String> NETHER_PLANTS = Set.of("crimson_roots", "warped_roots", "nether_sprouts",
			"nether_wart_block", "warped_wart_block", "shroomlight");

	private final Logger log;
	private final Path configDir;
	private final LootEngine engine;
	private final UniquePowers powers;

	private volatile Map<String, List<Pick>> pools = Map.of();
	private volatile Layered chances = new Layered(null, null);
	private volatile Layered cooldownCfg = new Layered(null, null);
	private volatile Layered multipliers = new Layered(null, null);
	private volatile boolean enabled = true;
	private volatile int memoryTicks = 6000;
	private volatile boolean pityOn;
	private volatile boolean pityPerActivity = true;
	private volatile int pityStart = 300;
	private volatile double pityStep = 0.01;
	private volatile double pityMax = 8.0;

	private final Map<String, Long> cooldownUntil = new HashMap<>();
	private final Map<Long, Long> seenPos = new LinkedHashMap<>();
	private final Map<UUID, int[]> statLast = new HashMap<>();
	private final Map<UUID, Map<String, Integer>> pity = new HashMap<>();
	private final List<Watch> watches = new ArrayList<>();
	private final List<BrushWatch> brushes = new ArrayList<>();
	private final Map<Long, Integer> stands = new HashMap<>();
	private boolean pityDirty;
	private long tick;

	Activities(Logger log, Path configDir, LootEngine engine, UniquePowers powers) {
		this.log = log;
		this.configDir = configDir;
		this.engine = engine;
		this.powers = powers;
	}

	// ------------------------------------------------------------------ config

	/** (Re)reads uniques/activities.json and rebuilds the activity pools from every entry's "sources" list. */
	void reload(Collection<LootEntry> entries) {
		JsonObject base = Settings.readResource("/lootsurge/defaults/uniques/activities.json", log);
		JsonObject user = null;
		Path file = configDir.resolve("uniques").resolve("activities.json");
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (parsed.isJsonObject()) {
					user = parsed.getAsJsonObject();
				}
			} catch (IOException | RuntimeException e) {
				log.error("[LootSurge] Could not read {} ({}). Using defaults.", file, e.getMessage());
			}
		}
		Layered root = new Layered(user, base);
		enabled = root.bool("enabled", true);
		chances = root.child("chances");
		cooldownCfg = root.child("cooldownTicks");
		multipliers = root.child("materialMultipliers");
		memoryTicks = Layered.clamp(root.integer("placedBlockMemoryTicks", 6000), 0, 1_000_000);
		Layered pc = root.child("pity");
		pityOn = pc.bool("enabled", false);
		pityPerActivity = pc.bool("perActivity", true);
		pityStart = Layered.clamp(pc.integer("startAfter", 300), 0, 1_000_000);
		pityStep = Layered.clamp(pc.number("bonusPerRoll", 0.01), 0.0, 100.0);
		pityMax = Layered.clamp(pc.number("maxMultiplier", 8.0), 1.0, 1000.0);

		Map<String, List<Pick>> map = new HashMap<>();
		for (LootEntry e : entries) {
			for (LootEntry.Source s : e.sources) {
				map.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(new Pick(e, s.weight()));
			}
		}
		this.pools = map;
		if (pityOn) {
			loadPity();
		}
		log.info("[LootSurge] Activity drops {}: {} material pools, pity {}.", enabled ? "on" : "OFF", map.size(),
				pityOn ? "on" : "off");
	}

	// ------------------------------------------------------------------ events

	void register() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
				onBreak(sl, sp, pos, state);
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
				guard(() -> onUseEntity(sl, sp, sp.getItemInHand(hand), entity));
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
				guard(() -> onUseBlock(sl, sp, sp.getItemInHand(hand), hit));
			}
			return InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
				ItemStack held = sp.getItemInHand(hand);
				if (held.is(Items.BUCKET)) {
					guard(() -> onBucket(sl, sp));
				}
			}
			return InteractionResult.PASS;
		});
	}

	private void guard(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			log.error("[LootSurge] Error while rolling an activity drop (nothing else is affected).", e);
		}
	}

	private void onBreak(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState state) {
		guard(() -> {
			Kind k = classify(state);
			if (k == null) {
				return;
			}
			if (k.cat().equals("farming") && !mature(state)) {
				return;
			}
			brushes.removeIf(b -> b.pos.equals(pos));
			roll(p, level, k.cat(), k.group(), k.material(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, pos, state);
		});
	}

	private void onUseEntity(ServerLevel level, ServerPlayer p, ItemStack held, Entity entity) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		if (held.is(Items.SHEARS) && entity instanceof Shearable sh && sh.readyForShearing()) {
			roll(p, level, "shear", "animal", type, entity.getX(), entity.getY(0.5), entity.getZ(), null, null);
		} else if (held.is(Items.BUCKET) && entity instanceof LivingEntity le && !le.isBaby()
				&& (type.equals("cow") || type.equals("goat") || type.equals("mooshroom"))) {
			roll(p, level, "milk", "animal", type, entity.getX(), entity.getY(0.5), entity.getZ(), null, null);
		} else if (entity instanceof TamableAnimal ta && !ta.isTame()) {
			watch(level, p, entity, "tame", type);
		} else if (entity instanceof AbstractHorse horse && !horse.isTamed()) {
			watch(level, p, entity, "horse", type);
		}
	}

	private void watch(ServerLevel level, ServerPlayer p, Entity entity, String cat, String material) {
		for (Watch w : watches) {
			if (w.entity.equals(entity.getUUID()) && w.player.equals(p.getUUID())) {
				w.expire = tick + 1200;
				return;
			}
		}
		if (watches.size() < 256) {
			watches.add(new Watch(level, p.getUUID(), entity.getUUID(), cat, material, tick + 1200));
		}
	}

	private void onUseBlock(ServerLevel level, ServerPlayer p, ItemStack held, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5
				&& (held.is(Items.SHEARS) || held.is(Items.GLASS_BOTTLE))) {
			roll(p, level, "honey", "hive", BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), pos.getX() + 0.5,
					pos.getY() + 1.0, pos.getZ() + 0.5, pos, state);
		} else if (held.is(Items.BRUSH) && (state.is(Blocks.SUSPICIOUS_SAND) || state.is(Blocks.SUSPICIOUS_GRAVEL))) {
			boolean known = false;
			for (BrushWatch b : brushes) {
				known |= b.pos.equals(pos);
			}
			if (!known && brushes.size() < 128) {
				brushes.add(new BrushWatch(level, pos.immutable(), p.getUUID(), state.is(Blocks.SUSPICIOUS_SAND) ? "sand" : "gravel",
						tick + 600));
			}
		} else if (state.is(Blocks.BREWING_STAND) && level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand) {
			int sig = 0;
			boolean potions = false;
			for (int i = 0; i < 3; i++) {
				ItemStack st = stand.getItem(i);
				PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
				potions |= pc != null && pc.hasEffects();
				sig = sig * 31 + Objects.hash(st.getItem(), pc);
			}
			long key = pos.asLong() * 31L + level.dimension().identifier().hashCode();
			Integer last = stands.put(key, sig);
			if (stands.size() > 512) {
				stands.clear();
			}
			if (potions && (last == null || last != sig)) {
				roll(p, level, "brewing", "potion", "potion", pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, null, null);
			}
		}
	}

	private void onBucket(ServerLevel level, ServerPlayer p) {
		Vec3 eye = p.getEyePosition();
		Vec3 to = eye.add(p.getLookAngle().scale(5.0));
		BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		FluidState fluid = level.getFluidState(pos);
		if (!fluid.isSource()) {
			return;
		}
		if (fluid.is(FluidTags.WATER)) {
			roll(p, level, "water", "fluid", "water", pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, pos, null);
		} else if (fluid.is(FluidTags.LAVA)) {
			roll(p, level, "lava", "fluid", "lava", pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, pos, null);
		}
	}

	/** ENTITY_LOAD: a freshly laid egg counts as egg collection for the nearest player. */
	void onEntityLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof ItemEntity drop) || !drop.getItem().is(Items.EGG) || drop.getAge() > 0) {
			return;
		}
		guard(() -> {
			Player near = level.getNearestPlayer(drop, 8.0);
			if (near instanceof ServerPlayer sp) {
				roll(sp, level, "egg", "animal", "chicken", drop.getX(), drop.getY(), drop.getZ(), null, null);
			}
		});
	}

	// ------------------------------------------------------------------ per-tick work

	void tick(MinecraftServer server) {
		tick++;
		if (tick % 10 == 0 && (!watches.isEmpty() || !brushes.isEmpty())) {
			guard(() -> checkWatches(server));
		}
		if (tick % 20 == 0 && enabled) {
			guard(() -> pollStats(server));
		}
		if (tick % 1200 == 0) {
			cooldownUntil.values().removeIf(until -> until < tick);
			seenPos.values().removeIf(until -> until < tick);
			if (pityDirty) {
				savePity();
			}
		}
	}

	private void checkWatches(MinecraftServer server) {
		Iterator<Watch> it = watches.iterator();
		while (it.hasNext()) {
			Watch w = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(w.player);
			Entity e = w.level.getEntity(w.entity);
			if (p == null || e == null || e.isRemoved() || tick >= w.expire) {
				it.remove();
				continue;
			}
			boolean tamed = (e instanceof TamableAnimal ta && ta.isTame()) || (e instanceof AbstractHorse h && h.isTamed());
			if (tamed) {
				it.remove();
				roll(p, w.level, w.cat, "animal", w.material, e.getX(), e.getY(0.5), e.getZ(), null, null);
			}
		}
		Iterator<BrushWatch> bit = brushes.iterator();
		while (bit.hasNext()) {
			BrushWatch b = bit.next();
			ServerPlayer p = server.getPlayerList().getPlayer(b.player);
			BlockState st = b.level.getBlockState(b.pos);
			if (p == null || tick >= b.expire) {
				bit.remove();
			} else if (!st.is(Blocks.SUSPICIOUS_SAND) && !st.is(Blocks.SUSPICIOUS_GRAVEL)) {
				bit.remove();
				roll(p, b.level, "archaeology", "dig", b.material, b.pos.getX() + 0.5, b.pos.getY() + 0.5, b.pos.getZ() + 0.5,
						b.pos, null);
			}
		}
	}

	/** Fishing and breeding have no event we can hook without mixins, so the vanilla stat counters are compared once a second. */
	private void pollStats(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			int fish = p.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
			int bred = p.getStats().getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED));
			int[] last = statLast.put(p.getUUID(), new int[] { fish, bred });
			if (last == null || !(p.level() instanceof ServerLevel level)) {
				continue;
			}
			if (fish > last[0]) {
				roll(p, level, "fishing", "fish", "fish", p.getX(), p.getY() + 1.0, p.getZ(), null, null);
			}
			if (bred > last[1]) {
				roll(p, level, "breed", "animal", "animal", p.getX(), p.getY() + 1.0, p.getZ(), null, null);
			}
		}
	}

	void onStop() {
		if (pityDirty) {
			savePity();
		}
		watches.clear();
		brushes.clear();
	}

	// ------------------------------------------------------------------ rolling

	private void roll(ServerPlayer p, ServerLevel level, String cat, String group, String material, double x, double y,
			double z, BlockPos pos, BlockState state) {
		if (p.isCreative() || p.isSpectator()) {
			return;
		}
		// Powers of worn/held Uniques react to the activity regardless of the drop roll below.
		powers.fire(p, level, cat, new UniquePowers.Ctx(p, level, x, y, z, pos, state));

		Settings s = engine.settings();
		if (!enabled || s == null || !s.enabled) {
			return;
		}
		List<Pick> candidates = candidates(cat, group, material);
		if (candidates.isEmpty()) {
			return;
		}
		long posKey = 0L;
		if (pos != null && memoryTicks > 0) {
			posKey = pos.asLong() * 31L + level.dimension().identifier().hashCode();
			Long until = seenPos.get(posKey);
			if (until != null && tick < until) {
				return;
			}
		}
		String cooldownKey = p.getUUID() + ":" + cat;
		Long cu = cooldownUntil.get(cooldownKey);
		if (cu != null && tick < cu) {
			return;
		}
		cooldownUntil.put(cooldownKey, tick + Layered.clamp(cooldownCfg.integer(cat, DEFAULT_COOLDOWN.getOrDefault(cat, 40)), 0, 1_000_000));
		if (pos != null && memoryTicks > 0) {
			seenPos.put(posKey, tick + memoryTicks);
			if (seenPos.size() > 8192) {
				Iterator<Long> it = seenPos.keySet().iterator();
				it.next();
				it.remove();
			}
		}

		double base = Layered.clamp(chances.number(cat, DEFAULT_CHANCE.getOrDefault(cat, 0.001)), 0.0, 1.0);
		double mult = multipliers.number(cat + ":" + material, multipliers.number(cat + ":" + group, 1.0));
		String pityKey = pityPerActivity ? cat : "all";
		double chance = Math.min(1.0, base * Math.max(0.0, mult) * pityMultiplier(p.getUUID(), pityKey));

		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (rnd.nextDouble() >= chance) {
			if (pityOn) {
				pity.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).merge(pityKey, 1, Integer::sum);
				pityDirty = true;
			}
			return;
		}
		if (dropUnique(p, level, candidates, x, y, z, pos == null ? p.getY() : pos.getY(), rnd, cat + ":" + material) && pityOn) {
			Map<String, Integer> mine = pity.get(p.getUUID());
			if (mine != null) {
				mine.remove(pityKey);
				pityDirty = true;
			}
		}
	}

	private boolean dropUnique(ServerPlayer p, ServerLevel level, List<Pick> candidates, double x, double y, double z,
			double feetY, ThreadLocalRandom rnd, String label) {
		Settings s = engine.settings();
		double total = 0.0;
		for (Pick pick : candidates) {
			total += pick.weight();
		}
		double r = rnd.nextDouble() * total;
		LootEntry chosen = candidates.get(candidates.size() - 1).entry();
		for (Pick pick : candidates) {
			r -= pick.weight();
			if (r < 0.0) {
				chosen = pick.entry();
				break;
			}
		}
		ItemStack stack = chosen.create(rnd);
		if (stack.isEmpty()) {
			return false;
		}
		boolean ok = engine.spawn(level, x, y, z, feetY, Tier.UNIQUE, new ArrayList<>(List.of(stack)), p, s, rnd);
		if (ok && s.debugLogging) {
			log.info("[LootSurge] {} -> unique {} for {}.", label, chosen.uniqueId, p.getName().getString());
		}
		return ok;
	}

	private List<Pick> candidates(String cat, String group, String material) {
		Map<String, List<Pick>> map = this.pools;
		List<Pick> out = new ArrayList<>();
		for (String key : new java.util.LinkedHashSet<>(List.of(cat + ":" + material, cat + ":" + group, cat + ":*"))) {
			List<Pick> list = map.get(key);
			if (list != null) {
				out.addAll(list);
			}
		}
		return out;
	}

	/** Test helper for /lootsurge activityroll: skips every chance, cooldown and the creative check. */
	String forceRoll(ServerPlayer p, String cat, String material) {
		if (!(p.level() instanceof ServerLevel level)) {
			return "Not available here.";
		}
		List<Pick> list = candidates(cat, material, material);
		if (list.isEmpty()) {
			return "No Unique has a source '" + cat + ":" + material + "' (or " + cat + ":*).";
		}
		boolean ok = dropUnique(p, level, list, p.getX(), p.getY() + 1.0, p.getZ(), p.getY(), ThreadLocalRandom.current(),
				"forced " + cat + ":" + material);
		return ok ? null : "Nothing dropped.";
	}

	// ------------------------------------------------------------------ pity

	private double pityMultiplier(UUID id, String key) {
		if (!pityOn) {
			return 1.0;
		}
		Map<String, Integer> mine = pity.get(id);
		int n = mine == null ? 0 : mine.getOrDefault(key, 0);
		if (n <= pityStart) {
			return 1.0;
		}
		return Math.min(pityMax, 1.0 + (n - pityStart) * pityStep);
	}

	private void loadPity() {
		pity.clear();
		Path file = configDir.resolve("pity.json");
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				return;
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				try {
					Map<String, Integer> counts = new HashMap<>();
					for (Map.Entry<String, JsonElement> c : e.getValue().getAsJsonObject().entrySet()) {
						counts.put(c.getKey(), c.getValue().getAsInt());
					}
					pity.put(UUID.fromString(e.getKey()), counts);
				} catch (RuntimeException ex) {
					log.warn("[LootSurge] Skipped a bad entry in pity.json: {}", e.getKey());
				}
			}
		} catch (IOException | RuntimeException e) {
			log.error("[LootSurge] Could not read pity.json ({}). Counters start at zero.", e.getMessage());
		}
	}

	private void savePity() {
		pityDirty = false;
		JsonObject root = new JsonObject();
		for (Map.Entry<UUID, Map<String, Integer>> e : pity.entrySet()) {
			if (e.getValue().isEmpty()) {
				continue;
			}
			JsonObject counts = new JsonObject();
			for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
				counts.addProperty(c.getKey(), c.getValue());
			}
			root.add(e.getKey().toString(), counts);
		}
		try {
			Files.createDirectories(configDir);
			Files.writeString(configDir.resolve("pity.json"), root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("[LootSurge] Could not save pity.json: {}", e.getMessage());
		}
	}

	/** Current dry-streak counters of a player, for /lootsurge pity. */
	Map<String, Integer> pityOf(UUID id) {
		Map<String, Integer> m = pity.get(id);
		return m == null ? Map.of() : Collections.unmodifiableMap(m);
	}

	boolean pityEnabled() {
		return pityOn;
	}

	// ------------------------------------------------------------------ classification

	private static Kind classify(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

		if (state.is(BlockTags.LOGS) || path.equals("bamboo")) {
			String p = path.startsWith("stripped_") ? path.substring(9) : path;
			for (String suffix : new String[] { "_log", "_wood", "_stem", "_hyphae", "_block" }) {
				if (p.endsWith(suffix)) {
					p = p.substring(0, p.length() - suffix.length());
					break;
				}
			}
			return new Kind("woodcutting", "wood", p);
		}
		if (path.endsWith("_ore") || path.equals("ancient_debris")) {
			String m = path.startsWith("deepslate_") ? path.substring(10) : path;
			m = m.endsWith("_ore") ? m.substring(0, m.length() - 4) : m;
			return new Kind("mining", "ore", m);
		}
		if (STONE.contains(path)) {
			return new Kind("mining", "stone", path);
		}
		if (DIG.contains(path)) {
			String group = path.contains("sand") ? "sand" : path.contains("snow") ? "snow" : path.contains("gravel") ? "gravel"
					: path.contains("clay") ? "clay" : path.contains("mud") ? "mud" : path.contains("moss") ? "moss" : "dirt";
			return new Kind("excavation", group, path);
		}
		if (state.is(BlockTags.CROPS) || CROPS.contains(path)) {
			return new Kind("farming", "crop", path);
		}
		if (state.is(BlockTags.FLOWERS)) {
			return new Kind("nature", "flower", path);
		}
		if (path.endsWith("_leaves")) {
			return new Kind("nature", "leaves", path);
		}
		if (path.endsWith("_sapling") || path.equals("mangrove_propagule")) {
			return new Kind("nature", "sapling", path);
		}
		if (VINES.contains(path)) {
			return new Kind("nature", "vine", path);
		}
		if (MUSHROOMS.contains(path)) {
			return new Kind("nature", "mushroom", path);
		}
		if (NETHER_PLANTS.contains(path)) {
			return new Kind("nature", "nether", path);
		}
		if (GRASS.contains(path)) {
			return new Kind("nature", "plant", path);
		}
		return null;
	}

	/** Crops only count once fully grown (blocks with an "age" property); melons, pumpkins and similar always count. */
	private static boolean mature(BlockState state) {
		for (Property<?> prop : state.getProperties()) {
			if (prop instanceof IntegerProperty ip && prop.getName().equals("age")) {
				return state.getValue(ip) >= Collections.max(ip.getPossibleValues());
			}
		}
		return true;
	}
}
