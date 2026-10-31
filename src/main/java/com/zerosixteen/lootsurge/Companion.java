package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Hiro's Spirit: a tribute to a husky named Hiro. Right-clicking the item calls up a spectral wolf companion that
 * glows, sparkles with snow, fights beside its owner, and sniffs out nearby ore and loot. It is a normal tamed wolf
 * (so vanilla AI makes it follow and defend the player), marked with a tag so the mod can remove it when its time is up.
 * Only one companion exists per player, and leftover ones from a previous session are removed on load.
 */
final class Companion {
	static final String TAG = "lootsurge_hiro";
	private static final int SNIFF_EVERY = 240;
	private static final int SNIFF_RADIUS = 12;

	private static final class Active {
		final UUID wolf;
		final long expire;
		long nextSniff;

		Active(UUID wolf, long expire, long nextSniff) {
			this.wolf = wolf;
			this.expire = expire;
			this.nextSniff = nextSniff;
		}
	}

	private final Map<UUID, Active> active = new HashMap<>();
	private final List<Entity> strays = new ArrayList<>();

	void clear() {
		active.clear();
		strays.clear();
	}

	/** ENTITY_LOAD: a Hiro wolf that is not ours (left over from a previous session) is queued for removal. */
	void onEntityLoad(Entity entity) {
		if (entity.getTags().contains(TAG)) {
			for (Active a : active.values()) {
				if (a.wolf.equals(entity.getUUID())) {
					return;
				}
			}
			strays.add(entity);
		}
	}

	void summon(ServerPlayer owner, ServerLevel level, int seconds, long tick) {
		Active old = active.remove(owner.getUUID());
		if (old != null && level.getEntity(old.wolf) != null) {
			level.getEntity(old.wolf).discard();
		}
		Wolf wolf = EntityType.WOLF.create(level, EntitySpawnReason.EVENT);
		if (wolf == null) {
			return;
		}
		int life = Math.max(30, seconds) * 20;
		wolf.setPos(owner.getX() + 1.0, owner.getY(), owner.getZ() + 1.0);
		wolf.tame(owner);
		wolf.setCustomName(Component.literal("Hiro").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		wolf.setCustomNameVisible(true);
		wolf.addTag(TAG);
		wolf.setPersistenceRequired();
		wolf.addEffect(new MobEffectInstance(MobEffects.GLOWING, life, 0, true, false, false));
		wolf.addEffect(new MobEffectInstance(MobEffects.SPEED, life, 1, true, false, false));
		wolf.addEffect(new MobEffectInstance(MobEffects.STRENGTH, life, 2, true, false, false));
		wolf.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, life, 1, true, false, false));
		wolf.addEffect(new MobEffectInstance(MobEffects.REGENERATION, life, 1, true, false, false));
		wolf.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, life, 0, true, false, false));
		// Registered before spawning so the ENTITY_LOAD stray check does not mistake it for a leftover.
		active.put(owner.getUUID(), new Active(wolf.getUUID(), tick + life, tick + 100));
		if (!level.addFreshEntity(wolf)) {
			active.remove(owner.getUUID());
			return;
		}
		howl(level, wolf);
		burst(level, wolf, 40);
		owner.sendSystemMessage(Component.literal("Hiro's spirit answers your call.").withStyle(ChatFormatting.AQUA), true);
	}

	void tick(MinecraftServer server, long tick) {
		if (!strays.isEmpty()) {
			for (Entity e : strays) {
				if (!e.isRemoved()) {
					e.discard();
				}
			}
			strays.clear();
		}
		if (active.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Active>> it = active.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Active> en = it.next();
			Active a = en.getValue();
			ServerPlayer owner = server.getPlayerList().getPlayer(en.getKey());
			Entity found = owner == null ? null : ((ServerLevel) owner.level()).getEntity(a.wolf);
			if (owner == null || found == null || found.isRemoved() || tick >= a.expire) {
				if (found != null && !found.isRemoved()) {
					burst((ServerLevel) found.level(), found, 30);
					found.discard();
				} else if (owner == null) {
					discardEverywhere(server, a.wolf);
				}
				it.remove();
				continue;
			}
			ServerLevel level = (ServerLevel) found.level();
			if (tick % 6 == 0) {
				sparkle(level, found);
			}
			if (found.distanceTo(owner) > 40.0f && owner.level() == found.level()) {
				found.setPos(owner.getX(), owner.getY(), owner.getZ());
			}
			if (tick >= a.nextSniff) {
				a.nextSniff = tick + SNIFF_EVERY;
				sniff(level, owner, found);
			}
		}
	}

	private static void discardEverywhere(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
	}

	private static void sparkle(ServerLevel level, Entity wolf) {
		ParticleOptions snow = Ids.particle("minecraft:snowflake");
		ParticleOptions rod = Ids.particle("minecraft:end_rod");
		if (snow != null) {
			level.sendParticles(snow, wolf.getX(), wolf.getY(0.6), wolf.getZ(), 4, 0.35, 0.4, 0.35, 0.01);
		}
		if (rod != null) {
			level.sendParticles(rod, wolf.getX(), wolf.getY(0.8), wolf.getZ(), 1, 0.3, 0.3, 0.3, 0.005);
		}
	}

	private static void burst(ServerLevel level, Entity wolf, int count) {
		ParticleOptions snow = Ids.particle("minecraft:snowflake");
		ParticleOptions rod = Ids.particle("minecraft:end_rod");
		if (snow != null) {
			level.sendParticles(snow, wolf.getX(), wolf.getY(0.5), wolf.getZ(), count, 0.6, 0.6, 0.6, 0.05);
		}
		if (rod != null) {
			level.sendParticles(rod, wolf.getX(), wolf.getY(0.5), wolf.getZ(), count / 2, 0.5, 0.6, 0.5, 0.04);
		}
	}

	/** There is no vanilla howl sound, so it is a slow, low wolf call over a soft bell, played twice. */
	private static void howl(ServerLevel level, Entity wolf) {
		SoundEvent call = Ids.sound("minecraft:entity.wolf.ambient");
		SoundEvent bell = Ids.sound("minecraft:block.amethyst_block.resonate");
		SoundEvent big = Ids.sound("minecraft:entity.wolf_big.ambient");
		if (call != null) {
			level.playSound((Player) null, wolf.getX(), wolf.getY(), wolf.getZ(), call, SoundSource.NEUTRAL, 2.0f, 0.5f);
		}
		if (big != null) {
			level.playSound((Player) null, wolf.getX(), wolf.getY(), wolf.getZ(), big, SoundSource.NEUTRAL, 2.0f, 0.55f);
		}
		if (bell != null) {
			level.playSound((Player) null, wolf.getX(), wolf.getY(), wolf.getZ(), bell, SoundSource.NEUTRAL, 1.5f, 1.4f);
		}
	}

	/** Every few seconds Hiro points out the nearest ore or dropped item near the owner. */
	private static void sniff(ServerLevel level, ServerPlayer owner, Entity wolf) {
		BlockPos c = owner.blockPosition();
		BlockPos best = null;
		String bestName = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(c.offset(-SNIFF_RADIUS, -SNIFF_RADIUS, -SNIFF_RADIUS),
				c.offset(SNIFF_RADIUS, SNIFF_RADIUS, SNIFF_RADIUS))) {
			if (!level.hasChunkAt(pos)) {
				continue;
			}
			BlockState st = level.getBlockState(pos);
			if (st.isAir()) {
				continue;
			}
			String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
			if (path.endsWith("_ore") || path.equals("ancient_debris")) {
				double d = pos.distSqr(c);
				if (d < bestDist) {
					bestDist = d;
					best = pos.immutable();
					bestName = path.replace('_', ' ');
				}
			}
		}
		ItemEntity nearestItem = null;
		double itemDist = Double.MAX_VALUE;
		for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(16.0))) {
			double d = ie.distanceToSqr(owner);
			if (d < itemDist) {
				itemDist = d;
				nearestItem = ie;
			}
		}
		Vec3 target = null;
		String text = null;
		if (best != null) {
			target = Vec3.atCenterOf(best);
			text = "Hiro sniffs out " + bestName + " " + direction(owner, target) + " (" + (int) Math.sqrt(bestDist) + " blocks)";
		} else if (nearestItem != null) {
			target = nearestItem.position();
			text = "Hiro found loot " + direction(owner, target) + " (" + (int) Math.sqrt(itemDist) + " blocks)";
			nearestItem.setGlowingTag(true);
		}
		if (target == null) {
			return;
		}
		owner.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA), true);
		ParticleOptions snow = Ids.particle("minecraft:snowflake");
		if (snow != null) {
			Vec3 from = wolf.position().add(0.0, 0.8, 0.0);
			Vec3 step = target.subtract(from).scale(1.0 / 10.0);
			for (int i = 1; i <= 10; i++) {
				Vec3 p = from.add(step.scale(i));
				level.sendParticles(snow, p.x(), p.y(), p.z(), 2, 0.05, 0.05, 0.05, 0.0);
			}
			level.sendParticles(snow, target.x(), target.y(), target.z(), 12, 0.4, 0.4, 0.4, 0.02);
		}
	}

	private static String direction(ServerPlayer from, Vec3 to) {
		double dx = to.x() - from.getX();
		double dz = to.z() - from.getZ();
		double dy = to.y() - from.getY();
		String h;
		if (Math.abs(dx) < 2.0 && Math.abs(dz) < 2.0) {
			h = "right here";
		} else {
			double ang = Math.toDegrees(Math.atan2(dz, dx));
			String[] names = { "east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east" };
			h = "to the " + names[(int) Math.floorMod(Math.round(ang / 45.0), 8L)];
		}
		return h + (dy > 3.0 ? ", above" : dy < -3.0 ? ", below" : "");
	}
}
