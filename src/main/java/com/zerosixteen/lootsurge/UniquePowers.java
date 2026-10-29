package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The powers of "Unique" items. A Unique stack carries a custom-data flag named {@code lootsurge_unique_<id>};
 * the powers for each id come from the pool JSON and live only in memory, so they cannot be edited into an item.
 * Everything runs on the server thread.
 */
final class UniquePowers {
	static final String KEY_PREFIX = "lootsurge_unique_";
	private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
			EquipmentSlot.FEET, EquipmentSlot.MAINHAND };
	private static final Set<String> IMMUNITIES = Set.of("fire", "explosion", "fall", "drowning", "projectile", "lightning");
	private static final int PULSE_TICKS = 10;
	private static final int EFFECT_TICKS = 240;
	private static final int SHIELD_TICKS = 60;

	record EffectSpec(Holder<MobEffect> effect, int amplifier, int seconds) {
	}

	record Powers(List<EffectSpec> effects, List<Holder<MobEffect>> cure, Set<String> immune, double lightningChance,
			double igniteSeconds, double explosionChance, float explosionPower, double lifesteal,
			List<EffectSpec> targetEffects, String shoot, int shootPower, int cooldownTicks) {
	}

	private volatile Map<String, Powers> registry = Map.of();
	private final Map<UUID, Long> nextShot = new HashMap<>();
	/** Wielders who just used a blast/lightning/fire power are briefly immune to their own blast. */
	private final Map<UUID, Long> shielded = new HashMap<>();
	private long tick;

	void setRegistry(Map<String, Powers> map) {
		this.registry = Map.copyOf(map);
	}

	void clear() {
		nextShot.clear();
		shielded.clear();
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
		return new Powers(List.copyOf(effects), List.copyOf(cure), Set.copyOf(immune),
				Layered.clamp(hit.number("lightningChance", 0.0), 0.0, 1.0),
				Layered.clamp(hit.number("igniteSeconds", 0.0), 0.0, 60.0),
				Layered.clamp(hit.number("explosionChance", 0.0), 0.0, 1.0),
				(float) Layered.clamp(hit.number("explosionPower", 0.0), 0.0, 10.0),
				Layered.clamp(hit.number("lifesteal", 0.0), 0.0, 2.0), List.copyOf(targetEffects), shoot,
				Layered.clamp(use.integer("power", 2), 0, 10), Layered.clamp(use.integer("cooldownTicks", 40), 5, 2400));
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

	private Powers find(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Map<String, Powers> reg = this.registry;
		if (reg.isEmpty()) {
			return null;
		}
		CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		for (Map.Entry<String, Powers> e : reg.entrySet()) {
			if (data.contains(KEY_PREFIX + e.getKey())) {
				return e.getValue();
			}
		}
		return null;
	}

	private List<Powers> worn(LivingEntity entity) {
		List<Powers> out = new ArrayList<>(2);
		for (EquipmentSlot slot : SLOTS) {
			Powers p = find(entity.getItemBySlot(slot));
			if (p != null) {
				out.add(p);
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

	// ------------------------------------------------------------------ events

	/** END_SERVER_TICK: passive effects, cures, and cleanup. */
	void tick(MinecraftServer server) {
		tick++;
		if (tick % PULSE_TICKS != 0) {
			return;
		}
		if (registry.isEmpty()) {
			return;
		}
		shielded.values().removeIf(until -> until + 40 < tick);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (isShielded(p)) {
				p.clearFire();
			}
			for (Powers w : worn(p)) {
				for (Holder<MobEffect> h : w.cure()) {
					if (p.hasEffect(h)) {
						p.removeEffect(h);
					}
				}
				for (EffectSpec spec : w.effects()) {
					p.addEffect(new MobEffectInstance(spec.effect(), EFFECT_TICKS, spec.amplifier(), true, false, true));
				}
			}
		}
	}

	/** ALLOW_DAMAGE: immunities of worn Unique items. Returns false to cancel the damage. */
	boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer p) || registry.isEmpty()) {
			return true;
		}
		if (isShielded(p) && (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypes.LIGHTNING_BOLT)
				|| source.is(DamageTypeTags.IS_FIRE))) {
			return false;
		}
		for (Powers w : worn(p)) {
			for (String s : w.immune()) {
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

	/** AFTER_DAMAGE: on-hit powers of the Unique item in the attacker's main hand. */
	void afterDamage(LivingEntity victim, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		try {
			if (blocked || registry.isEmpty() || !(source.getEntity() instanceof ServerPlayer p) || victim == p) {
				return;
			}
			if (!(victim.level() instanceof ServerLevel level)) {
				return;
			}
			Powers w = find(p.getMainHandItem());
			if (w == null) {
				return;
			}
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

	/** UseItemCallback: right-click to fire a fireball, for items with an onUse power. */
	InteractionResult onUse(Player player, Level level, InteractionHand hand) {
		try {
			if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl) || registry.isEmpty()) {
				return InteractionResult.PASS;
			}
			Powers w = find(player.getItemInHand(hand));
			if (w == null || w.shoot() == null) {
				return InteractionResult.PASS;
			}
			Long due = nextShot.get(sp.getUUID());
			if (due != null && tick < due) {
				return InteractionResult.PASS;
			}
			nextShot.put(sp.getUUID(), tick + w.cooldownTicks());
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
}
