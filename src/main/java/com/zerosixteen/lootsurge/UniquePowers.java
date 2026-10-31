package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The powers of "Unique" items. A Unique stack carries a custom-data flag named {@code lootsurge_unique_<id>};
 * the powers for each id come from the JSON files and live only in memory, so they cannot be edited into an item.
 * Everything runs on the server thread.
 *
 * <p>Powers come in four kinds: passive effects and immunities, on-hit effects, a right-click power, and
 * <b>triggers</b>: lists of actions that run when the wearer does an activity (chops wood, shears a sheep, gets hurt...).
 * Triggers have a chance and a per-player cooldown so tree-fellers and vein-miners cannot fire them once per block.
 */
final class UniquePowers {
	static final String KEY_PREFIX = "lootsurge_unique_";
	static final String TEMP_TAG = "lootsurge_temp";
	private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
			EquipmentSlot.FEET, EquipmentSlot.MAINHAND };
	private static final Set<String> IMMUNITIES = Set.of("fire", "explosion", "fall", "drowning", "projectile", "lightning");
	private static final Set<String> FLAGS = Set.of("lava_walk", "magnet", "glow_hostiles", "hunger_saver", "sprint_burst",
			"last_stand");
	private static final Set<String> ACTIONS = Set.of("drop", "duplicate", "xp", "effect", "effect_nearby", "sound",
			"particles", "ring", "column", "lightning", "shockwave", "grow", "spawn", "reveal", "pull", "heal", "teleport",
			"repair", "summon_hiro");
	private static final int PULSE_TICKS = 10;
	private static final int EFFECT_TICKS = 240;
	private static final int SHIELD_TICKS = 60;
	private static final int DEFAULT_TRIGGER_COOLDOWN = 20;

	record EffectSpec(Holder<MobEffect> effect, int amplifier, int seconds) {
	}

	record Drop(Item item, int min, int max, double weight) {
	}

	/** One thing a trigger does. Unused fields keep their defaults. */
	record Action(String type, double chance, double radius, double amount, int count, int seconds, int amplifier,
			Holder<MobEffect> effect, ParticleOptions particle, SoundEvent sound, float volume, float pitch, String id,
			boolean give, List<Drop> drops) {
	}

	record Trigger(Set<String> on, double chance, int cooldown, List<Action> actions) {
	}

	record Powers(List<EffectSpec> effects, List<Holder<MobEffect>> cure, Set<String> immune, double lightningChance,
			double igniteSeconds, double explosionChance, float explosionPower, double lifesteal,
			List<EffectSpec> targetEffects, String shoot, int shootPower, int cooldownTicks, List<Trigger> triggers,
			Set<String> flags, Map<String, Double> params, List<Action> useActions) {
		double param(String key, double def) {
			Double v = params.get(key);
			return v == null ? def : v;
		}
	}

	record Held(String id, Powers powers) {
	}

	/** Where a trigger happened. state/pos are only set for block activities. */
	record Ctx(ServerPlayer player, ServerLevel level, double x, double y, double z, BlockPos pos, BlockState state) {
	}

	private static final class Temp {
		final ServerLevel level;
		final BlockPos pos;
		long expire;

		Temp(ServerLevel level, BlockPos pos, long expire) {
			this.level = level;
			this.pos = pos;
			this.expire = expire;
		}
	}

	private static final class Timed {
		final Entity entity;
		final long expire;

		Timed(Entity entity, long expire) {
			this.entity = entity;
			this.expire = expire;
		}
	}

	private volatile Map<String, Powers> registry = Map.of();
	private final Map<UUID, Long> nextShot = new HashMap<>();
	private final Map<String, Long> cooldowns = new HashMap<>();
	/** Wielders who just used a blast/lightning/fire power are briefly immune to their own blast. */
	private final Map<UUID, Long> shielded = new HashMap<>();
	private final Map<UUID, List<Powers>> flagged = new HashMap<>();
	private final Map<UUID, Integer> charge = new HashMap<>();
	private final Map<UUID, long[]> pullBurst = new HashMap<>();
	private final Map<Long, Temp> temps = new HashMap<>();
	private final List<Timed> summoned = new ArrayList<>();
	private final List<Entity> strayTemps = new ArrayList<>();
	private final Companion companion = new Companion();
	private long tick;

	void setRegistry(Map<String, Powers> map) {
		this.registry = Map.copyOf(map);
	}

	Companion companion() {
		return companion;
	}

	void clear() {
		revertAllTemps();
		nextShot.clear();
		cooldowns.clear();
		shielded.clear();
		flagged.clear();
		charge.clear();
		pullBurst.clear();
		for (Timed t : summoned) {
			if (!t.entity.isRemoved()) {
				t.entity.discard();
			}
		}
		summoned.clear();
		companion.clear();
	}

	// ------------------------------------------------------------------ parsing

	static Powers parse(Layered c, Logger log, String where) {
		List<EffectSpec> effects = effectList(c.array("effects"), log, where + ".powers.effects", 0);
		List<Holder<MobEffect>> cure = new ArrayList<>();
		JsonArray cureArr = c.array("cure");
		if (cureArr != null) {
			for (JsonElement e : cureArr) {
				Holder<MobEffect> h = e.isJsonPrimitive() ? effect(e.getAsString()) : null;
				if (h == null) {
					log.warn("[LootSurge] {}: unknown cure effect {}, ignored.", where, e);
				} else {
					cure.add(h);
				}
			}
		}
		Set<String> immune = new HashSet<>();
		JsonArray immuneArr = c.array("immune");
		if (immuneArr != null) {
			for (JsonElement e : immuneArr) {
				String s = e.isJsonPrimitive() ? e.getAsString().trim().toLowerCase(Locale.ROOT) : "";
				if (IMMUNITIES.contains(s)) {
					immune.add(s);
				} else {
					log.warn("[LootSurge] {}: unknown immunity {} (use {}), ignored.", where, e, IMMUNITIES);
				}
			}
		}
		Layered hit = c.child("onHit");
		List<EffectSpec> targetEffects = effectList(hit.array("effects"), log, where + ".powers.onHit.effects", 10);
		Layered use = c.child("onUse");
		String shoot = use.string("shoot", "none").trim().toLowerCase(Locale.ROOT);
		if (!shoot.equals("large") && !shoot.equals("small")) {
			shoot = null;
		}

		Set<String> flags = new HashSet<>();
		JsonArray flagArr = c.array("flags");
		if (flagArr != null) {
			for (JsonElement e : flagArr) {
				String f = e.isJsonPrimitive() ? e.getAsString().trim().toLowerCase(Locale.ROOT) : "";
				if (FLAGS.contains(f)) {
					flags.add(f);
				} else {
					log.warn("[LootSurge] {}: unknown flag {} (use {}), ignored.", where, e, FLAGS);
				}
			}
		}
		Map<String, Double> params = new HashMap<>();
		JsonElement paramEl = c.raw("params");
		if (paramEl != null && paramEl.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : paramEl.getAsJsonObject().entrySet()) {
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
					params.put(e.getKey(), e.getValue().getAsDouble());
				}
			}
		}

		List<Trigger> triggers = new ArrayList<>();
		JsonArray trigArr = c.array("triggers");
		if (trigArr != null) {
			int ti = 0;
			for (JsonElement e : trigArr) {
				if (e.isJsonObject()) {
					Trigger t = parseTrigger(e.getAsJsonObject(), log, where + ".powers.triggers[" + ti + "]");
					if (t != null) {
						triggers.add(t);
					}
				}
				ti++;
			}
		}
		List<Action> useActions = actionList(use.array("actions"), log, where + ".powers.onUse.actions");

		return new Powers(List.copyOf(effects), List.copyOf(cure), Set.copyOf(immune),
				Layered.clamp(hit.number("lightningChance", 0.0), 0.0, 1.0),
				Layered.clamp(hit.number("igniteSeconds", 0.0), 0.0, 60.0),
				Layered.clamp(hit.number("explosionChance", 0.0), 0.0, 1.0),
				(float) Layered.clamp(hit.number("explosionPower", 0.0), 0.0, 10.0),
				Layered.clamp(hit.number("lifesteal", 0.0), 0.0, 2.0), List.copyOf(targetEffects), shoot,
				Layered.clamp(use.integer("power", 2), 0, 10), Layered.clamp(use.integer("cooldownTicks", 40), 5, 72000),
				List.copyOf(triggers), Set.copyOf(flags), Map.copyOf(params), useActions);
	}

	private static Trigger parseTrigger(JsonObject o, Logger log, String where) {
		Layered c = new Layered(o, null);
		Set<String> on = new HashSet<>();
		JsonArray onArr = c.array("on");
		if (onArr != null) {
			for (JsonElement e : onArr) {
				if (e.isJsonPrimitive()) {
					on.add(e.getAsString().trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		if (on.isEmpty()) {
			log.warn("[LootSurge] {}: trigger has no \"on\" list, skipped.", where);
			return null;
		}
		List<Action> actions = actionList(c.array("actions"), log, where + ".actions");
		if (actions.isEmpty()) {
			log.warn("[LootSurge] {}: trigger has no valid actions, skipped.", where);
			return null;
		}
		return new Trigger(Set.copyOf(on), Layered.clamp(c.number("chance", 1.0), 0.0, 1.0),
				Layered.clamp(c.integer("cooldown", DEFAULT_TRIGGER_COOLDOWN), 0, 72000), actions);
	}

	private static List<Action> actionList(JsonArray arr, Logger log, String where) {
		List<Action> out = new ArrayList<>();
		if (arr == null) {
			return out;
		}
		int i = 0;
		for (JsonElement e : arr) {
			if (e.isJsonObject()) {
				Action a = parseAction(e.getAsJsonObject(), log, where + "[" + i + "]");
				if (a != null) {
					out.add(a);
				}
			}
			i++;
		}
		return out;
	}

	private static Action parseAction(JsonObject o, Logger log, String where) {
		Layered c = new Layered(o, null);
		String type = c.string("type", "").trim().toLowerCase(Locale.ROOT);
		if (!ACTIONS.contains(type)) {
			log.warn("[LootSurge] {}: unknown action type '{}' (use {}), skipped.", where, type, ACTIONS);
			return null;
		}
		Holder<MobEffect> eff = null;
		String effId = c.string("effect", "");
		if (!effId.isEmpty()) {
			eff = effect(effId);
			if (eff == null) {
				log.warn("[LootSurge] {}: unknown effect '{}', action skipped.", where, effId);
				return null;
			}
		}
		ParticleOptions particle = null;
		String pid = c.string("particle", "");
		if (!pid.isEmpty()) {
			particle = Ids.particle(pid);
			if (particle == null) {
				log.warn("[LootSurge] {}: unknown or unsupported particle '{}', action skipped.", where, pid);
				return null;
			}
		}
		SoundEvent sound = null;
		String sid = c.string("sound", "");
		if (!sid.isEmpty()) {
			sound = Ids.sound(sid);
			if (sound == null) {
				log.warn("[LootSurge] {}: unknown sound '{}', action skipped.", where, sid);
				return null;
			}
		}
		List<Drop> drops = new ArrayList<>();
		JsonArray items = c.array("items");
		if (items != null) {
			for (JsonElement e : items) {
				if (!e.isJsonObject()) {
					continue;
				}
				Layered d = new Layered(e.getAsJsonObject(), null);
				Item item = Ids.item(d.string("item", ""));
				if (item == null) {
					log.warn("[LootSurge] {}: unknown item '{}' in drop list, ignored.", where, d.string("item", ""));
					continue;
				}
				int min = Layered.clamp(d.integer("min", 1), 1, 64);
				int max = Layered.clamp(d.integer("max", min), min, 64);
				drops.add(new Drop(item, min, max, Layered.clamp(d.number("weight", 1.0), 0.0001, 1_000_000.0)));
			}
		}
		if (type.equals("drop") && drops.isEmpty()) {
			log.warn("[LootSurge] {}: drop action has no valid items, skipped.", where);
			return null;
		}
		if (type.startsWith("effect") && eff == null) {
			log.warn("[LootSurge] {}: effect action needs an \"effect\", skipped.", where);
			return null;
		}
		String id = c.string("id", "").trim().toLowerCase(Locale.ROOT);
		if (type.equals("spawn") && !Ids.entityTypeExists(id)) {
			log.warn("[LootSurge] {}: unknown entity '{}', spawn action skipped.", where, id);
			return null;
		}
		return new Action(type, Layered.clamp(c.number("chance", 1.0), 0.0, 1.0),
				Layered.clamp(c.number("radius", 0.0), 0.0, 16.0), Layered.clamp(c.number("amount", 0.0), -64.0, 4096.0),
				Layered.clamp(c.integer("count", 1), 1, 64), Layered.clamp(c.integer("seconds", 5), 0, 600),
				Layered.clamp(c.integer("amplifier", 0), 0, 50), eff, particle, sound,
				(float) Layered.clamp(c.number("volume", 1.0), 0.0, 4.0),
				(float) Layered.clamp(c.number("pitch", 1.0), 0.1, 2.0), id, c.bool("give", false), List.copyOf(drops));
	}

	private static List<EffectSpec> effectList(JsonArray arr, Logger log, String where, int defaultSeconds) {
		List<EffectSpec> out = new ArrayList<>();
		if (arr == null) {
			return out;
		}
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) {
				continue;
			}
			Layered c = new Layered(e.getAsJsonObject(), null);
			String id = c.string("id", "");
			Holder<MobEffect> h = effect(id);
			if (h == null) {
				log.warn("[LootSurge] {}: unknown effect '{}', ignored.", where, id);
				continue;
			}
			out.add(new EffectSpec(h, Layered.clamp(c.integer("amplifier", 0), 0, 50),
					Layered.clamp(c.integer("seconds", defaultSeconds), 0, 600)));
		}
		return out;
	}

	private static Holder<MobEffect> effect(String raw) {
		Identifier id = Ids.parse(raw);
		if (id == null) {
			return null;
		}
		MobEffect e = BuiltInRegistries.MOB_EFFECT.getOptional(id).orElse(null);
		return e == null ? null : BuiltInRegistries.MOB_EFFECT.wrapAsHolder(e);
	}

	// ------------------------------------------------------------------ lookup

	private Held find(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Map<String, Powers> reg = this.registry;
		if (reg.isEmpty()) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag();
		for (String key : tag.keySet()) {
			if (key.startsWith(KEY_PREFIX)) {
				String id = key.substring(KEY_PREFIX.length());
				Powers p = reg.get(id);
				if (p != null) {
					return new Held(id, p);
				}
			}
		}
		return null;
	}

	private List<Held> worn(LivingEntity entity) {
		List<Held> out = new ArrayList<>(2);
		for (EquipmentSlot slot : SLOTS) {
			Held h = find(entity.getItemBySlot(slot));
			if (h != null) {
				out.add(h);
			}
		}
		return out;
	}

	private boolean isShielded(Player p) {
		Long until = shielded.get(p.getUUID());
		return until != null && tick < until;
	}

	private void shield(Player p) {
		shielded.put(p.getUUID(), tick + SHIELD_TICKS);
	}

	// ------------------------------------------------------------------ tick

	/** END_SERVER_TICK: passive effects, flags, temporary blocks and summons. */
	void tick(MinecraftServer server) {
		tick++;
		companion.tick(server, tick);
		if (registry.isEmpty() && temps.isEmpty() && summoned.isEmpty() && strayTemps.isEmpty()) {
			return;
		}

		if (tick % 4 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				List<Powers> mine = new ArrayList<>(2);
				for (Held h : worn(p)) {
					if (!h.powers().flags().isEmpty()) {
						mine.add(h.powers());
					}
				}
				if (mine.isEmpty()) {
					flagged.remove(p.getUUID());
				} else {
					flagged.put(p.getUUID(), mine);
				}
			}
		}
		if (!flagged.isEmpty() || !pullBurst.isEmpty()) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				fastFlags(p);
			}
		}
		if (tick % 5 == 0) {
			expireTemps(false);
			expireSummons();
		}

		if (tick % PULSE_TICKS != 0 || registry.isEmpty()) {
			return;
		}
		shielded.values().removeIf(until -> until + 40 < tick);
		cooldowns.values().removeIf(until -> until < tick);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (isShielded(p)) {
				p.clearFire();
			}
			for (Held h : worn(p)) {
				Powers w = h.powers();
				for (Holder<MobEffect> eff : w.cure()) {
					if (p.hasEffect(eff)) {
						p.removeEffect(eff);
					}
				}
				for (EffectSpec spec : w.effects()) {
					p.addEffect(new MobEffectInstance(spec.effect(), EFFECT_TICKS, spec.amplifier(), true, false, true));
				}
				if (w.flags().contains("glow_hostiles") && p.level() instanceof ServerLevel) {
					double r = w.param("glowRadius", 24.0);
					for (Monster m : p.level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(r))) {
						m.addEffect(new MobEffectInstance(MobEffects.GLOWING, 50, 0, true, false, false));
					}
				}
				if (w.flags().contains("hunger_saver") && tick % (int) Math.max(20, w.param("hungerSeconds", 10.0) * 20) < PULSE_TICKS
						&& p.getFoodData().getFoodLevel() < 20) {
					p.addEffect(new MobEffectInstance(MobEffects.SATURATION, 1, 0, true, false, false));
				}
			}
		}
	}

	/** Every-tick work for players that wear a flagged Unique: lava walking, magnets, sprint charge. */
	private void fastFlags(ServerPlayer p) {
		List<Powers> list = flagged.get(p.getUUID());
		if (list != null && p.level() instanceof ServerLevel level) {
			for (Powers w : list) {
				if (w.flags().contains("lava_walk") && !p.isShiftKeyDown()) {
					lavaWalk(level, p, (int) w.param("lavaSeconds", 4.0) * 20);
				}
				if (w.flags().contains("magnet") && !p.isShiftKeyDown() && tick % 2 == 0) {
					magnet(level, p, w.param("magnetRadius", 7.0), 30);
				}
				if (w.flags().contains("sprint_burst")) {
					sprintCharge(level, p, w);
				}
			}
		}
		long[] burst = pullBurst.get(p.getUUID());
		if (burst != null) {
			if (tick >= burst[0] || !(p.level() instanceof ServerLevel burstLevel)) {
				pullBurst.remove(p.getUUID());
			} else if (tick % 2 == 0) {
				magnet(burstLevel, p, burst[1], 0);
			}
		}
	}

	private void magnet(ServerLevel level, ServerPlayer p, double radius, int minAge) {
		AABB box = p.getBoundingBox().inflate(radius);
		Vec3 target = p.position().add(0.0, 0.6, 0.0);
		for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
			if (ie.getAge() >= minAge) {
				pull(ie, target);
			}
		}
		for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, box)) {
			pull(orb, target);
		}
	}

	private static void pull(Entity e, Vec3 target) {
		Vec3 to = target.subtract(e.position());
		double d = to.length();
		if (d < 0.7) {
			return;
		}
		e.setDeltaMovement(to.normalize().scale(Math.min(0.55, 0.18 + 0.5 / d)));
	}

	private void lavaWalk(ServerLevel level, ServerPlayer p, int ticks) {
		BlockPos base = p.blockPosition().below();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos pos = base.offset(dx, 0, dz);
				BlockState state = level.getBlockState(pos);
				long key = tempKey(level, pos);
				if (state.is(Blocks.LAVA) && state.getFluidState().isSource()) {
					level.setBlock(pos, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
					temps.put(key, new Temp(level, pos, tick + ticks));
				} else if (state.is(Blocks.MAGMA_BLOCK) && temps.containsKey(key)) {
					temps.get(key).expire = tick + ticks;
				}
			}
		}
	}

	private static long tempKey(ServerLevel level, BlockPos pos) {
		return pos.asLong() * 31L + level.dimension().identifier().hashCode();
	}

	private void expireTemps(boolean all) {
		Iterator<Temp> it = temps.values().iterator();
		while (it.hasNext()) {
			Temp t = it.next();
			if (all || tick >= t.expire) {
				if (t.level.getBlockState(t.pos).is(Blocks.MAGMA_BLOCK)) {
					t.level.setBlock(t.pos, Blocks.LAVA.defaultBlockState(), 3);
				}
				it.remove();
			}
		}
	}

	private void revertAllTemps() {
		expireTemps(true);
	}

	private void expireSummons() {
		if (!strayTemps.isEmpty()) {
			for (Entity e : strayTemps) {
				if (!e.isRemoved()) {
					e.discard();
				}
			}
			strayTemps.clear();
		}
		Iterator<Timed> it = summoned.iterator();
		while (it.hasNext()) {
			Timed t = it.next();
			if (t.entity.isRemoved()) {
				it.remove();
			} else if (tick >= t.expire) {
				if (t.entity.level() instanceof ServerLevel level) {
					level.sendParticles(Ids.particle("minecraft:poof"), t.entity.getX(), t.entity.getY(0.5), t.entity.getZ(), 8,
							0.2, 0.2, 0.2, 0.02);
				}
				t.entity.discard();
				it.remove();
			}
		}
	}

	private void sprintCharge(ServerLevel level, ServerPlayer p, Powers w) {
		int need = (int) w.param("chargeTicks", 100.0);
		int c = charge.getOrDefault(p.getUUID(), 0);
		if (p.isSprinting()) {
			c = Math.min(need, c + 1);
			if (c == need) {
				level.sendParticles(Ids.particle("minecraft:electric_spark"), p.getX(), p.getY(0.5), p.getZ(), 3, 0.3, 0.5, 0.3,
						0.05);
			}
		} else if (c >= need) {
			c = 0;
			fire(p, level, "sprint_burst", new Ctx(p, level, p.getX(), p.getY(), p.getZ(), p.blockPosition(), null));
		} else {
			c = Math.max(0, c - 2);
		}
		charge.put(p.getUUID(), c);
	}

	// ------------------------------------------------------------------ damage events

	/** ALLOW_DAMAGE: immunities of worn Unique items. Returns false to cancel the damage. */
	boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer p) || registry.isEmpty()) {
			return true;
		}
		if (isShielded(p) && (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypes.LIGHTNING_BOLT)
				|| source.is(DamageTypeTags.IS_FIRE))) {
			return false;
		}
		for (Held h : worn(p)) {
			for (String s : h.powers().immune()) {
				boolean match = switch (s) {
					case "fire" -> source.is(DamageTypeTags.IS_FIRE);
					case "explosion" -> source.is(DamageTypeTags.IS_EXPLOSION);
					case "fall" -> source.is(DamageTypeTags.IS_FALL);
					case "drowning" -> source.is(DamageTypeTags.IS_DROWNING);
					case "projectile" -> source.is(DamageTypeTags.IS_PROJECTILE);
					case "lightning" -> source.is(DamageTypes.LIGHTNING_BOLT);
					default -> false;
				};
				if (match) {
					return false;
				}
			}
		}
		return true;
	}

	/** ALLOW_DEATH: "last_stand" items cancel one lethal hit, then go on a long cooldown. */
	boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		try {
			if (!(entity instanceof ServerPlayer p) || registry.isEmpty() || !(p.level() instanceof ServerLevel level)) {
				return true;
			}
			for (Held h : worn(p)) {
				if (!h.powers().flags().contains("last_stand")) {
					continue;
				}
				String key = p.getUUID() + ":last_stand";
				Long until = cooldowns.get(key);
				if (until != null && tick < until) {
					continue;
				}
				cooldowns.put(key, tick + (long) h.powers().param("lastStandCooldownTicks", 6000.0));
				p.setHealth((float) Math.max(2.0, h.powers().param("lastStandHealth", 8.0)));
				p.clearFire();
				fire(p, level, "last_stand", new Ctx(p, level, p.getX(), p.getY(), p.getZ(), p.blockPosition(), null));
				return false;
			}
		} catch (RuntimeException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Error in a Unique last-stand power.", e);
		}
		return true;
	}

	/** AFTER_DAMAGE: on-hit powers of the attacker's weapon, plus the "hurt" and "hit" triggers. */
	void afterDamage(LivingEntity victim, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		try {
			if (registry.isEmpty() || !(victim.level() instanceof ServerLevel level)) {
				return;
			}
			if (victim instanceof ServerPlayer vp && damageTaken > 0.0f) {
				fire(vp, level, "hurt", new Ctx(vp, level, vp.getX(), vp.getY(), vp.getZ(), vp.blockPosition(), null));
			}
			if (blocked || !(source.getEntity() instanceof ServerPlayer p) || victim == p) {
				return;
			}
			fire(p, level, "hit", new Ctx(p, level, victim.getX(), victim.getY(0.5), victim.getZ(), victim.blockPosition(), null));
			Held held = find(p.getMainHandItem());
			if (held == null) {
				return;
			}
			Powers w = held.powers();
			ThreadLocalRandom rnd = ThreadLocalRandom.current();
			if (w.lifesteal() > 0.0 && damageTaken > 0.0f) {
				p.heal((float) (damageTaken * w.lifesteal()));
			}
			for (EffectSpec spec : w.targetEffects()) {
				victim.addEffect(new MobEffectInstance(spec.effect(), Math.max(1, spec.seconds()) * 20,
						spec.amplifier(), false, true));
			}
			if (w.igniteSeconds() > 0.0) {
				victim.igniteForSeconds((float) w.igniteSeconds());
			}
			if (w.lightningChance() > 0.0 && rnd.nextDouble() < w.lightningChance()) {
				shield(p);
				Identifier id = Ids.parse("minecraft:lightning_bolt");
				EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
				Entity bolt = type == null ? null : type.create(level, EntitySpawnReason.EVENT);
				if (bolt != null) {
					bolt.setPos(victim.getX(), victim.getY(), victim.getZ());
					level.addFreshEntity(bolt);
				}
			}
			if (w.explosionPower() > 0.0f && w.explosionChance() > 0.0 && rnd.nextDouble() < w.explosionChance()) {
				shield(p);
				level.explode(null, null, null, new Vec3(victim.getX(), victim.getY(0.5), victim.getZ()),
						w.explosionPower(), false, Level.ExplosionInteraction.NONE);
			}
		} catch (RuntimeException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Error in a Unique on-hit power.", e);
		}
	}

	/** UseItemCallback: right-click to fire a fireball or run the item's onUse actions. */
	InteractionResult onUse(Player player, Level level, InteractionHand hand) {
		try {
			if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl) || registry.isEmpty()) {
				return InteractionResult.PASS;
			}
			Held held = find(player.getItemInHand(hand));
			if (held == null) {
				return InteractionResult.PASS;
			}
			Powers w = held.powers();
			if (w.shoot() == null && w.useActions().isEmpty()) {
				return InteractionResult.PASS;
			}
			Long due = nextShot.get(sp.getUUID());
			if (due != null && tick < due) {
				return InteractionResult.PASS;
			}
			nextShot.put(sp.getUUID(), tick + w.cooldownTicks());

			if (!w.useActions().isEmpty()) {
				Ctx cx = new Ctx(sp, sl, sp.getX(), sp.getY(), sp.getZ(), sp.blockPosition(), null);
				ThreadLocalRandom rnd = ThreadLocalRandom.current();
				for (Action a : w.useActions()) {
					run(a, cx, rnd);
				}
			}
			if (w.shoot() == null) {
				return InteractionResult.SUCCESS;
			}
			shield(sp);

			Vec3 look = sp.getLookAngle();
			Entity fireball = w.shoot().equals("small") ? new SmallFireball(sl, sp, look)
					: new LargeFireball(sl, sp, look, w.shootPower());
			fireball.setPos(sp.getX() + look.x() * 1.5, sp.getEyeY() + look.y() * 1.5 - 0.1, sp.getZ() + look.z() * 1.5);
			sl.addFreshEntity(fireball);

			SoundEvent sound = Ids.sound("minecraft:entity.ghast.shoot");
			if (sound != null) {
				sl.playSound((Player) null, sp.getX(), sp.getY(), sp.getZ(), sound, SoundSource.PLAYERS, 1.0f, 1.0f);
			}
			return InteractionResult.SUCCESS;
		} catch (RuntimeException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Error in a Unique on-use power.", e);
			return InteractionResult.PASS;
		}
	}

	// ------------------------------------------------------------------ triggers and actions

	/**
	 * Runs the triggers of every Unique the player wears or holds that listens for this activity. Each trigger has its
	 * own per-player cooldown, so one tree-feller swing or vein-miner sweep fires it once, not once per block.
	 */
	void fire(ServerPlayer p, ServerLevel level, String activity, Ctx cx) {
		if (registry.isEmpty() || p.isCreative()) {
			return;
		}
		try {
			List<Held> list = worn(p);
			if (list.isEmpty()) {
				return;
			}
			ThreadLocalRandom rnd = ThreadLocalRandom.current();
			for (Held h : list) {
				int idx = 0;
				for (Trigger t : h.powers().triggers()) {
					int myIdx = idx++;
					if (!t.on().contains(activity)) {
						continue;
					}
					String key = p.getUUID() + ":" + h.id() + ":" + myIdx;
					Long until = cooldowns.get(key);
					if (until != null && tick < until) {
						continue;
					}
					if (t.chance() < 1.0 && rnd.nextDouble() >= t.chance()) {
						continue;
					}
					cooldowns.put(key, tick + t.cooldown());
					for (Action a : t.actions()) {
						run(a, cx, rnd);
					}
				}
			}
		} catch (RuntimeException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Error in a Unique trigger.", e);
		}
	}

	private void run(Action a, Ctx cx, ThreadLocalRandom rnd) {
		if (a.chance() < 1.0 && rnd.nextDouble() >= a.chance()) {
			return;
		}
		ServerPlayer p = cx.player();
		ServerLevel level = cx.level();
		switch (a.type()) {
			case "drop" -> {
				for (int i = 0; i < a.count(); i++) {
					Drop d = pickDrop(a.drops(), rnd);
					if (d != null) {
						ItemStack stack = new ItemStack(d.item(), 1);
						stack.setCount(Math.max(1, Math.min(d.min() + rnd.nextInt(d.max() - d.min() + 1), stack.getMaxStackSize())));
						give(cx, stack, a.give(), rnd);
					}
				}
			}
			case "duplicate" -> {
				if (cx.state() != null && cx.pos() != null) {
					for (ItemStack stack : Block.getDrops(cx.state(), level, cx.pos(), null)) {
						give(cx, stack, a.give(), rnd);
					}
				}
			}
			case "xp" -> ExperienceOrb.award(level, new Vec3(cx.x(), cx.y(), cx.z()), (int) a.amount());
			case "effect" -> p.addEffect(new MobEffectInstance(a.effect(), a.seconds() * 20, a.amplifier(), false, true, true));
			case "effect_nearby" -> {
				for (Monster m : level.getEntitiesOfClass(Monster.class,
						new AABB(cx.x() - a.radius(), cx.y() - a.radius(), cx.z() - a.radius(), cx.x() + a.radius(),
								cx.y() + a.radius(), cx.z() + a.radius()))) {
					m.addEffect(new MobEffectInstance(a.effect(), a.seconds() * 20, a.amplifier(), false, true));
				}
			}
			case "sound" -> {
				if (a.sound() != null) {
					level.playSound((Player) null, cx.x(), cx.y(), cx.z(), a.sound(), SoundSource.PLAYERS, a.volume(), a.pitch());
				}
			}
			case "particles" -> {
				if (a.particle() != null) {
					level.sendParticles(a.particle(), cx.x(), cx.y() + a.amount(), cx.z(), a.count(), a.radius(), a.radius(),
							a.radius(), 0.03);
				}
			}
			case "ring" -> {
				if (a.particle() != null) {
					int n = Math.max(8, a.count() * 8);
					for (int i = 0; i < n; i++) {
						double ang = Math.PI * 2.0 * i / n;
						level.sendParticles(a.particle(), cx.x() + Math.cos(ang) * a.radius(), cx.y() + 0.2 + a.amount(),
								cx.z() + Math.sin(ang) * a.radius(), 1, 0.0, 0.0, 0.0, 0.0);
					}
				}
			}
			case "column" -> {
				if (a.particle() != null) {
					for (int i = 0; i < a.count() * 4; i++) {
						level.sendParticles(a.particle(), cx.x(), cx.y() + i * 0.5, cx.z(), 2, a.radius(), 0.1, a.radius(), 0.01);
					}
				}
			}
			case "lightning" -> {
				LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
				if (bolt != null) {
					bolt.setPos(cx.x(), cx.y(), cx.z());
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
			}
			case "shockwave" -> {
				double r = Math.max(1.0, a.radius());
				for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(cx.x() - r, cx.y() - 2, cx.z() - r, cx.x() + r, cx.y() + 3, cx.z() + r))) {
					if (e == p) {
						continue;
					}
					Vec3 away = new Vec3(e.getX() - cx.x(), 0.0, e.getZ() - cx.z());
					Vec3 dir = away.lengthSqr() < 1.0e-4 ? new Vec3(0.0, 0.0, 0.0) : away.normalize();
					double force = a.amount() <= 0.0 ? 0.8 : a.amount();
					e.push(dir.x() * force, 0.35, dir.z() * force);
				}
			}
			case "grow" -> grow(level, cx, (int) Math.max(1, a.radius()), Math.max(1, a.count()));
			case "spawn" -> spawn(a, cx, rnd);
			case "reveal" -> reveal(a, cx);
			case "pull" -> pullBurst.put(p.getUUID(), new long[] { tick + a.seconds() * 20L, (long) Math.max(2.0, a.radius()) });
			case "heal" -> p.heal((float) a.amount());
			case "teleport" -> {
				for (int i = 0; i < 6; i++) {
					double r = Math.max(2.0, a.radius());
					double tx = p.getX() + (rnd.nextDouble() * 2.0 - 1.0) * r;
					double tz = p.getZ() + (rnd.nextDouble() * 2.0 - 1.0) * r;
					double ty = p.getY() + rnd.nextInt(5) - 2;
					if (p.randomTeleport(tx, ty, tz, true)) {
						SoundEvent s = Ids.sound("minecraft:entity.enderman.teleport");
						if (s != null) {
							level.playSound((Player) null, p.getX(), p.getY(), p.getZ(), s, SoundSource.PLAYERS, 1.0f, 1.0f);
						}
						break;
					}
				}
			}
			case "repair" -> {
				ItemStack held = p.getMainHandItem();
				if (held.isDamaged()) {
					held.setDamageValue(Math.max(0, held.getDamageValue() - (int) a.amount()));
				}
			}
			case "summon_hiro" -> companion.summon(p, level, a.seconds(), tick);
			default -> {
			}
		}
	}

	private static Drop pickDrop(List<Drop> drops, ThreadLocalRandom rnd) {
		double total = 0.0;
		for (Drop d : drops) {
			total += d.weight();
		}
		double roll = rnd.nextDouble() * total;
		for (Drop d : drops) {
			roll -= d.weight();
			if (roll < 0.0) {
				return d;
			}
		}
		return drops.isEmpty() ? null : drops.get(drops.size() - 1);
	}

	private void give(Ctx cx, ItemStack stack, boolean toInventory, ThreadLocalRandom rnd) {
		if (stack.isEmpty()) {
			return;
		}
		if (toInventory) {
			cx.player().getInventory().add(stack);
			if (stack.isEmpty()) {
				return;
			}
		}
		ItemEntity drop = new ItemEntity(cx.level(), cx.x(), cx.y() + 0.4, cx.z(), stack);
		drop.setDefaultPickUpDelay();
		drop.setDeltaMovement((rnd.nextDouble() - 0.5) * 0.2, 0.25, (rnd.nextDouble() - 0.5) * 0.2);
		cx.level().addFreshEntity(drop);
	}

	/** Instantly ripens fully-grown-able crops around the spot. No drops are created; the player still has to harvest. */
	private static void grow(ServerLevel level, Ctx cx, int radius, int max) {
		BlockPos c = cx.pos() != null ? cx.pos() : cx.player().blockPosition();
		int done = 0;
		for (BlockPos pos : BlockPos.betweenClosed(c.offset(-radius, -2, -radius), c.offset(radius, 2, radius))) {
			if (done >= max * 6) {
				break;
			}
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() instanceof CropBlock crop && !crop.isMaxAge(st)) {
				level.setBlock(pos, crop.getStateForAge(crop.getMaxAge()), 2);
				level.sendParticles(Ids.particle("minecraft:happy_villager"), pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
						4, 0.25, 0.2, 0.25, 0.0);
				done++;
			}
		}
	}

	private void spawn(Action a, Ctx cx, ThreadLocalRandom rnd) {
		Identifier id = Ids.parse(a.id());
		EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		if (type == null) {
			return;
		}
		for (int i = 0; i < a.count() && summoned.size() < 64; i++) {
			Entity e = type.create(cx.level(), EntitySpawnReason.EVENT);
			if (e == null) {
				return;
			}
			e.setPos(cx.x() + (rnd.nextDouble() - 0.5) * 2.0, cx.y() + 0.5, cx.z() + (rnd.nextDouble() - 0.5) * 2.0);
			if (a.effect() != null && e instanceof LivingEntity le) {
				le.addEffect(new MobEffectInstance(a.effect(), Math.max(1, a.seconds()) * 20, a.amplifier(), false, false));
			}
			Timed timed = null;
			if (a.seconds() > 0) {
				e.addTag(TEMP_TAG);
				timed = new Timed(e, tick + a.seconds() * 20L);
				summoned.add(timed);
			}
			if (!cx.level().addFreshEntity(e) && timed != null) {
				summoned.remove(timed);
			}
		}
	}

	/** ENTITY_LOAD: a temporary summon that was saved with its chunk (server stopped) is queued for removal. */
	void onEntityLoad(Entity entity) {
		if (entity.getTags().contains(TEMP_TAG)) {
			for (Timed t : summoned) {
				if (t.entity == entity) {
					return;
				}
			}
			strayTemps.add(entity);
		}
	}

	private static void reveal(Action a, Ctx cx) {
		ServerLevel level = cx.level();
		BlockPos c = cx.player().blockPosition();
		int r = (int) Math.max(2.0, Math.min(8.0, a.radius()));
		ParticleOptions particle = a.particle() != null ? a.particle() : Ids.particle("minecraft:happy_villager");
		int found = 0;
		for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
			if (found >= 40) {
				break;
			}
			if (!level.hasChunkAt(pos)) {
				continue;
			}
			BlockState st = level.getBlockState(pos);
			if (st.isAir()) {
				continue;
			}
			String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
			if (path.endsWith("_ore") || path.equals("ancient_debris") || path.equals("amethyst_cluster")) {
				level.sendParticles(particle, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
				found++;
			}
		}
		if (found > 0) {
			cx.player().sendSystemMessage(Component.literal("Something glitters nearby... (" + found + " deposits)")
					.withStyle(net.minecraft.ChatFormatting.AQUA), true);
		}
	}
}
