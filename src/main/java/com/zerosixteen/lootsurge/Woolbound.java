package com.zerosixteen.lootsurge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Woolbound Dream. Right-clicking it (in the air or on a block; it can never be placed) starts a dream that lasts
 * durationTicks of world time (default one Minecraft day): hunger and saturation stay full, and Resistance and Speed
 * are kept up. The expiry (game time) lives in memory and is mirrored into a player tag, so it survives death,
 * logout, restarts and dimension travel. Using it again while dreaming only tells the player how long is left.
 */
final class Woolbound {
	private static final String TAG = "lootsurge_woolbound:";

	private final UniquePowers uniques;
	private final Map<UUID, Long> until = new HashMap<>();
	private final Map<UUID, Long> nextUse = new HashMap<>();
	private long tick;

	Woolbound(UniquePowers uniques) {
		this.uniques = uniques;
	}

	void clear() {
		until.clear();
		nextUse.clear();
	}

	private static long now(MinecraftServer server) {
		return server.overworld().getGameTime();
	}

	/** UseBlockCallback and UseItemCallback. Returns SUCCESS when the dream item consumed the click. */
	InteractionResult use(Player player, Level level, InteractionHand hand) {
		try {
			if (!(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = p.getItemInHand(hand);
			if (stack.isEmpty()) {
				return InteractionResult.PASS;
			}
			UniquePowers.Held held = uniques.inspect(stack);
			if (held == null || !held.powers().flags().contains("woolbound")) {
				return InteractionResult.PASS;
			}
			if (hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.SUCCESS;
			}
			Long due = nextUse.get(p.getUUID());
			if (due != null && tick < due) {
				return InteractionResult.SUCCESS;
			}
			nextUse.put(p.getUUID(), tick + (long) held.powers().param("cooldownTicks", 40.0));
			long game = now(sl.getServer());
			Long current = until.get(p.getUUID());
			if (current != null && current > game) {
				long left = (current - game) / 20;
				p.sendSystemMessage(Component.literal("You are already dreaming (" + (left / 60) + "m " + (left % 60) + "s left).")
						.withStyle(ChatFormatting.LIGHT_PURPLE), true);
				return InteractionResult.SUCCESS;
			}
			long duration = (long) Math.max(200.0, Math.min(240000.0, held.powers().param("durationTicks", 24000.0)));
			until.put(p.getUUID(), game + duration);
			writeTag(p, game + duration);
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(20.0f);
			apply(p, held.powers());
			burst(sl, p);
			sound(sl, p, "minecraft:block.amethyst_block.chime", 1.0f, 0.8f);
			sound(sl, p, "minecraft:block.wool.place", 1.0f, 0.6f);
			p.sendSystemMessage(Component.literal("The Woolbound Dream wraps around you...").withStyle(ChatFormatting.LIGHT_PURPLE), true);
			return InteractionResult.SUCCESS;
		} catch (RuntimeException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Error in the Woolbound Dream.", e);
			return InteractionResult.PASS;
		}
	}

	/** END_SERVER_TICK. Once a second, keeps the dream going for everyone who has one. */
	void tick(MinecraftServer server) {
		tick++;
		if (until.isEmpty() || tick % 20 != 0) {
			return;
		}
		long game = now(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Long end = until.get(p.getUUID());
			if (end == null) {
				continue;
			}
			if (game >= end) {
				until.remove(p.getUUID());
				clearTag(p);
				p.sendSystemMessage(Component.literal("The dream fades.").withStyle(ChatFormatting.GRAY), true);
				continue;
			}
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(20.0f);
			apply(p, null);
			if (!hasTag(p)) {
				writeTag(p, end);
			}
			if (p.level() instanceof ServerLevel sl && game % 40 == 0) {
				ParticleOptions part = Ids.particle("minecraft:end_rod");
				if (part != null) {
					sl.sendParticles(part, p.getX(), p.getY(1.0), p.getZ(), 2, 0.4, 0.6, 0.4, 0.01);
				}
			}
		}
	}

	private int resistanceAmp = 1;
	private int speedAmp = 1;

	private void apply(ServerPlayer p, UniquePowers.Powers w) {
		if (w != null) {
			resistanceAmp = (int) Math.max(0, Math.min(4, w.param("resistanceAmp", 1.0)));
			speedAmp = (int) Math.max(0, Math.min(4, w.param("speedAmp", 1.0)));
		}
		buff(p, "minecraft:resistance", resistanceAmp);
		buff(p, "minecraft:speed", speedAmp);
	}

	private static void buff(ServerPlayer p, String id, int amp) {
		Identifier key = Ids.parse(id);
		MobEffect e = key == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(key).orElse(null);
		if (e != null) {
			Holder<MobEffect> h = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(e);
			p.addEffect(new MobEffectInstance(h, 100, amp, true, false, true));
		}
	}

	/** Player joined: restore the dream from the saved tag. */
	void onJoin(ServerPlayer p) {
		for (String t : p.entityTags()) {
			if (t.startsWith(TAG)) {
				try {
					until.put(p.getUUID(), Long.parseLong(t.substring(TAG.length())));
				} catch (NumberFormatException ignored) {
					p.removeTag(t);
				}
				return;
			}
		}
	}

	/** Respawn / return from the End: the new player object gets the tag again. */
	void onCopy(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		Long end = until.get(newPlayer.getUUID());
		if (end != null) {
			writeTag(newPlayer, end);
		}
	}

	private static boolean hasTag(ServerPlayer p) {
		for (String t : p.entityTags()) {
			if (t.startsWith(TAG)) {
				return true;
			}
		}
		return false;
	}

	private static void clearTag(ServerPlayer p) {
		for (String t : new java.util.ArrayList<>(p.entityTags())) {
			if (t.startsWith(TAG)) {
				p.removeTag(t);
			}
		}
	}

	private static void writeTag(ServerPlayer p, long end) {
		clearTag(p);
		p.addTag(TAG + end);
	}

	private static void burst(ServerLevel level, ServerPlayer p) {
		for (String id : new String[] { "minecraft:end_rod", "minecraft:cloud", "minecraft:happy_villager" }) {
			ParticleOptions part = Ids.particle(id);
			if (part != null) {
				level.sendParticles(part, p.getX(), p.getY(1.0), p.getZ(), 20, 0.6, 0.8, 0.6, 0.05);
			}
		}
	}

	private static void sound(ServerLevel level, ServerPlayer p, String id, float vol, float pitch) {
		SoundEvent s = Ids.sound(id);
		if (s != null) {
			level.playSound((Player) null, p.getX(), p.getY(), p.getZ(), s, SoundSource.PLAYERS, vol, pitch);
		}
	}
}
