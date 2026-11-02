package com.zerosixteen.lootsurge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Bleeding (damage over time) and Stun (brief immobilisation) for weapons with the "bleed"/"stun" flags.
 * Everything is bounded: bleeding refreshes instead of stacking, both effects have per-victim cooldowns, a stun is
 * always released (timer, death, unload, server stop, or the load-time tag cleanup below).
 */
final class Mechanics {
	static final String STUN_TAG = "lootsurge_stunned";

	private static final class Bleed {
		final LivingEntity victim;
		final UUID attacker;
		long expire;
		long nextTick;
		float damage;
		long immuneUntil;

		Bleed(LivingEntity victim, UUID attacker) {
			this.victim = victim;
			this.attacker = attacker;
		}
	}

	private static final class Stun {
		final LivingEntity victim;
		final boolean ownedAi;
		long expire;
		long immuneUntil;

		Stun(LivingEntity victim, boolean ownedAi) {
			this.victim = victim;
			this.ownedAi = ownedAi;
		}
	}

	private final Map<UUID, Bleed> bleeds = new HashMap<>();
	private final Map<UUID, Stun> stuns = new HashMap<>();
	private final Map<UUID, Long> bleedCooldown = new HashMap<>();
	private final Map<UUID, Long> stunCooldown = new HashMap<>();
	private boolean inBleedDamage;

	boolean inBleedDamage() {
		return inBleedDamage;
	}

	/** Called from UniquePowers after a hit by a wielder whose weapon has the flags. */
	void onHit(ServerPlayer p, LivingEntity victim, UniquePowers.Powers w, ServerLevel level, long tick) {
		if (victim == p || !victim.isAlive() || inBleedDamage) {
			return;
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (w.flags().contains("bleed") && rnd.nextDouble() < w.param("bleedChance", 0.25)) {
			Long cd = bleedCooldown.get(victim.getUUID());
			Bleed existing = bleeds.get(victim.getUUID());
			long duration = (long) (w.param("bleedSeconds", 5.0) * 20);
			if (existing != null) {
				existing.expire = Math.max(existing.expire, tick + duration); // refresh, never stack
			} else if (cd == null || tick >= cd) {
				Bleed b = new Bleed(victim, p.getUUID());
				b.expire = tick + duration;
				b.nextTick = tick + 20;
				b.damage = (float) Math.min(4.0, w.param("bleedDamage", 1.0));
				bleeds.put(victim.getUUID(), b);
				bleedCooldown.put(victim.getUUID(), tick + duration + (long) w.param("bleedCooldownTicks", 100.0));
				burst(level, victim, "minecraft:damage_indicator", 10);
				sound(level, victim, "minecraft:entity.player.hurt_sweet_berry_bush", 1.0f, 0.7f);
			}
		}
		if (w.flags().contains("stun") && rnd.nextDouble() < w.param("stunChance", 0.08)) {
			Long cd = stunCooldown.get(victim.getUUID());
			if (!stuns.containsKey(victim.getUUID()) && (cd == null || tick >= cd)) {
				int ticks = (int) Math.max(5, Math.min(60, victim instanceof ServerPlayer
						? Math.min(w.param("stunPlayerTicks", 20.0), 40.0) : w.param("stunTicks", 30.0)));
				stun(level, victim, ticks, tick);
				stunCooldown.put(victim.getUUID(), tick + ticks + (long) w.param("stunCooldownTicks", 200.0));
			}
		}
	}

	private void stun(ServerLevel level, LivingEntity victim, int ticks, long tick) {
		boolean owned = false;
		if (victim instanceof Mob mob) {
			if (!mob.isNoAi()) {
				mob.setNoAi(true);
				owned = true;
				mob.addTag(STUN_TAG);
			}
		} else {
			MobEffect slow = lookup("minecraft:slowness");
			if (slow != null) {
				victim.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(slow), ticks, 6, false, false, true));
			}
		}
		Stun s = new Stun(victim, owned);
		s.expire = tick + ticks;
		stuns.put(victim.getUUID(), s);
		burst(level, victim, "minecraft:crit", 16);
		sound(level, victim, "minecraft:entity.player.attack.knockback", 1.0f, 0.6f);
	}

	private void release(Stun s) {
		if (s.ownedAi && s.victim instanceof Mob mob) {
			mob.setNoAi(false);
			mob.removeTag(STUN_TAG);
		}
	}

	void tick(long tick) {
		if (bleeds.isEmpty() && stuns.isEmpty() && bleedCooldown.isEmpty() && stunCooldown.isEmpty()) {
			return;
		}
		Iterator<Stun> si = stuns.values().iterator();
		while (si.hasNext()) {
			Stun s = si.next();
			if (tick >= s.expire || !s.victim.isAlive() || s.victim.isRemoved()) {
				release(s);
				si.remove();
			}
		}
		Iterator<Bleed> bi = bleeds.values().iterator();
		while (bi.hasNext()) {
			Bleed b = bi.next();
			if (tick >= b.expire || !b.victim.isAlive() || b.victim.isRemoved()) {
				bi.remove();
				continue;
			}
			if (tick >= b.nextTick && b.victim.level() instanceof ServerLevel level) {
				b.nextTick = tick + 20;
				Entity owner = level.getServer().getPlayerList().getPlayer(b.attacker);
				inBleedDamage = true;
				try {
					b.victim.hurtServer(level, owner == null ? level.damageSources().magic()
							: level.damageSources().indirectMagic(owner, owner), b.damage);
				} finally {
					inBleedDamage = false;
				}
				burst(level, b.victim, "minecraft:damage_indicator", 4);
				burst(level, b.victim, "minecraft:crimson_spore", 6);
			}
		}
		if (tick % 200 == 0) {
			bleedCooldown.values().removeIf(until -> until < tick);
			stunCooldown.values().removeIf(until -> until < tick);
		}
	}

	/** ENTITY_LOAD: a mob saved while stunned (server stopped, chunk unloaded) wakes up. */
	void onEntityLoad(Entity entity) {
		if (entity instanceof Mob mob && mob.entityTags().contains(STUN_TAG) && !stuns.containsKey(mob.getUUID())) {
			mob.setNoAi(false);
			mob.removeTag(STUN_TAG);
		}
	}

	void clear() {
		for (Stun s : stuns.values()) {
			release(s);
		}
		stuns.clear();
		bleeds.clear();
		bleedCooldown.clear();
		stunCooldown.clear();
	}

	private static MobEffect lookup(String raw) {
		Identifier id = Ids.parse(raw);
		return id == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(id).orElse(null);
	}

	private static void burst(ServerLevel level, LivingEntity e, String particle, int count) {
		ParticleOptions p = Ids.particle(particle);
		if (p != null) {
			level.sendParticles(p, e.getX(), e.getY(0.6), e.getZ(), count, 0.25, 0.35, 0.25, 0.02);
		}
	}

	private static void sound(ServerLevel level, LivingEntity e, String id, float vol, float pitch) {
		SoundEvent s = Ids.sound(id);
		if (s != null) {
			level.playSound((net.minecraft.world.entity.player.Player) null, e.getX(), e.getY(), e.getZ(), s, SoundSource.PLAYERS, vol, pitch);
		}
	}
}
