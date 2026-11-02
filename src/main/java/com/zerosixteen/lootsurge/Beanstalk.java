package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Giant Beanstalk. Breaking grass has a tiny chance to make a magical beanstalk shoot up into the sky: a moss trunk
 * with a climbable vine, leafy tufts, and a small mossy platform with a treasure chest on top.
 *
 * <p>Safety: it only ever replaces air, replaceable plants and snow layers (never a player's blocks), it grows layer by
 * layer on the server tick and pauses while chunks are unloaded, it refuses to start near another stalk or without
 * open sky, it aborts and cleans up if something blocks it too early, and (by default) it withers away after a while,
 * removing only blocks that still match what it placed. Progress is saved in config/lootsurge/data/beanstalks.txt.
 */
final class Beanstalk {
	private static final class Stalk {
		final ResourceKey<Level> dim;
		final BlockPos base;
		final int height;
		int phase; // 0 growing, 1 grown, 2 withering, 3 aborting
		int index; // layers placed (growing) or layers still to remove (withering/aborting)
		long expire; // game time when a grown stalk starts to wither; 0 = permanent
		boolean treasure;

		Stalk(ResourceKey<Level> dim, BlockPos base, int height) {
			this.dim = dim;
			this.base = base;
			this.height = height;
		}
	}

	private final Logger log;
	private final Path configDir;
	private final LootEngine engine;
	private final List<Stalk> stalks = new ArrayList<>();
	private final Map<UUID, Long> cooldown = new HashMap<>();
	private boolean dirty;
	private long tick;

	private boolean enabled = true;
	private double chance = 0.0003;
	private int minHeight = 40;
	private int maxHeight = 80;
	private int ticksPerLayer = 2;
	private int cooldownSeconds = 900;
	private int lifetimeMinutes = 30;
	private int treasureRolls = 6;
	private int maxActive = 3;
	private int minDistance = 48;
	private int limitY = 300;
	private Set<String> dimensions = Set.of("minecraft:overworld");
	private boolean announce = true;

	Beanstalk(Logger log, Path configDir, LootEngine engine) {
		this.log = log;
		this.configDir = configDir;
		this.engine = engine;
	}

	// ------------------------------------------------------------------ config and persistence

	void reload() {
		JsonObject base = Settings.readResource("/lootsurge/defaults/beanstalk.json", log);
		JsonObject user = null;
		Path f = configDir.resolve("beanstalk.json");
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				JsonElement e = JsonParser.parseReader(r);
				if (e.isJsonObject()) {
					user = e.getAsJsonObject();
				}
			} catch (IOException | RuntimeException e) {
				log.error("[LootSurge] Could not read {} ({}). Using defaults.", f, e.getMessage());
			}
		}
		Layered c = new Layered(user, base);
		enabled = c.bool("enabled", true);
		chance = Layered.clamp(c.number("chance", 0.0003), 0.0, 1.0);
		minHeight = Layered.clamp(c.integer("minHeight", 40), 12, 200);
		maxHeight = Layered.clamp(c.integer("maxHeight", 80), minHeight, 250);
		ticksPerLayer = Layered.clamp(c.integer("ticksPerLayer", 2), 1, 40);
		cooldownSeconds = Layered.clamp(c.integer("cooldownSeconds", 900), 0, 1_000_000);
		lifetimeMinutes = Layered.clamp(c.integer("lifetimeMinutes", 30), 0, 100_000);
		treasureRolls = Layered.clamp(c.integer("treasureRolls", 6), 1, 20);
		maxActive = Layered.clamp(c.integer("maxActive", 3), 1, 20);
		minDistance = Layered.clamp(c.integer("minDistance", 48), 8, 1000);
		limitY = Layered.clamp(c.integer("maxY", 300), 64, 2000);
		announce = c.bool("announce", true);
		Set<String> dims = new HashSet<>();
		JsonArray arr = c.array("dimensions");
		if (arr != null) {
			for (JsonElement e : arr) {
				if (e.isJsonPrimitive()) {
					dims.add(e.getAsString().trim().toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		dimensions = dims.isEmpty() ? Set.of("minecraft:overworld") : Set.copyOf(dims);
	}

	private Path dataFile() {
		return configDir.resolve("data").resolve("beanstalks.txt");
	}

	void load() {
		stalks.clear();
		Path f = dataFile();
		if (!Files.isRegularFile(f)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String[] p = line.split("\\|");
				if (p.length != 9) {
					continue;
				}
				Identifier dimId = Ids.parse(p[0]);
				if (dimId == null) {
					continue;
				}
				Stalk s = new Stalk(ResourceKey.create(Registries.DIMENSION, dimId),
						new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), Integer.parseInt(p[4]));
				s.phase = Integer.parseInt(p[5]);
				s.index = Integer.parseInt(p[6]);
				s.expire = Long.parseLong(p[7]);
				s.treasure = Boolean.parseBoolean(p[8]);
				stalks.add(s);
			}
		} catch (IOException | RuntimeException e) {
			log.warn("[LootSurge] Could not read {} ({}). Unfinished beanstalks stay as they are.", f, e.getMessage());
		}
	}

	void save() {
		if (!dirty) {
			return;
		}
		try {
			Files.createDirectories(dataFile().getParent());
			List<String> lines = new ArrayList<>();
			for (Stalk s : stalks) {
				lines.add(s.dim.identifier() + "|" + s.base.getX() + "|" + s.base.getY() + "|" + s.base.getZ() + "|" + s.height + "|"
						+ s.phase + "|" + s.index + "|" + s.expire + "|" + s.treasure);
			}
			Path tmp = dataFile().resolveSibling("beanstalks.tmp");
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, dataFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (IOException e) {
			log.warn("[LootSurge] Could not save beanstalk progress ({}).", e.getMessage());
		}
	}

	void onStop() {
		save();
		cooldown.clear();
	}

	// ------------------------------------------------------------------ trigger

	/** PlayerBlockBreakEvents.AFTER: the (rare) magic when grass is broken. */
	void onBreak(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState state) {
		if (!enabled || chance <= 0.0 || p.isCreative() || p.isSpectator()) {
			return;
		}
		if (!(state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))) {
			return;
		}
		try {
			if (ThreadLocalRandom.current().nextDouble() >= chance) {
				return;
			}
			if (!dimensions.contains(level.dimension().identifier().toString())) {
				return;
			}
			if (!level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK) || !level.canSeeSky(pos)) {
				return;
			}
			long now = level.getServer().overworld().getGameTime();
			Long cd = cooldown.get(p.getUUID());
			if (cd != null && now < cd) {
				return;
			}
			int active = 0;
			for (Stalk s : stalks) {
				if (s.phase == 0 || s.phase == 2 || s.phase == 3) {
					active++;
				}
				if (s.dim.equals(level.dimension()) && Math.abs(s.base.getX() - pos.getX()) < minDistance
						&& Math.abs(s.base.getZ() - pos.getZ()) < minDistance && s.phase != 3) {
					return;
				}
			}
			if (active >= maxActive) {
				return;
			}
			int height = minHeight + ThreadLocalRandom.current().nextInt(maxHeight - minHeight + 1);
			height = Math.min(height, limitY - pos.getY() - 4);
			if (height < 12) {
				return;
			}
			cooldown.put(p.getUUID(), now + cooldownSeconds * 20L);
			Stalk s = new Stalk(level.dimension(), pos.immutable(), height);
			stalks.add(s);
			while (stalks.size() > 96) {
				stalks.remove(0);
			}
			dirty = true;
			if (announce) {
				p.sendSystemMessage(Component.literal("The ground trembles... a bean sprouts!").withStyle(ChatFormatting.GREEN), true);
			}
			sound(level, pos, "minecraft:block.amethyst_block.resonate", 1.5f, 0.6f);
			burst(level, pos, "minecraft:happy_villager", 30);
		} catch (RuntimeException e) {
			log.error("[LootSurge] Error while starting a Giant Beanstalk.", e);
		}
	}

	// ------------------------------------------------------------------ growth

	void tick(MinecraftServer server) {
		tick++;
		if (stalks.isEmpty()) {
			return;
		}
		if (tick % ticksPerLayer == 0) {
			Iterator<Stalk> it = stalks.iterator();
			while (it.hasNext()) {
				Stalk s = it.next();
				try {
					step(server, s);
				} catch (RuntimeException e) {
					log.error("[LootSurge] Error while growing a Giant Beanstalk; it is abandoned.", e);
					s.phase = 1;
					s.expire = 0;
					dirty = true;
				}
			}
			stalks.removeIf(s -> s.phase == 4);
		}
		if (tick % 200 == 0) {
			save();
		}
	}

	private void step(MinecraftServer server, Stalk s) {
		ServerLevel level = server.getLevel(s.dim);
		if (level == null) {
			return;
		}
		switch (s.phase) {
			case 0 -> grow(level, s);
			case 1 -> {
				if (s.expire > 0 && server.overworld().getGameTime() >= s.expire && level.hasChunkAt(s.base)) {
					s.phase = 2;
					s.index = s.height + 1;
					dirty = true;
				}
			}
			case 2, 3 -> wither(level, s);
			default -> {
			}
		}
	}

	private static boolean free(ServerLevel level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		return (st.isAir() || st.canBeReplaced()) && st.getFluidState().isEmpty();
	}

	/** Blocks of one layer, as (position, state) pairs. Layer height+1 is the chest. Deterministic, so it can be removed again. */
	private static List<Map.Entry<BlockPos, BlockState>> layer(Stalk s, int i) {
		List<Map.Entry<BlockPos, BlockState>> out = new ArrayList<>();
		int y = s.base.getY() + i;
		int x = s.base.getX();
		int z = s.base.getZ();
		BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();
		if (i < s.height) {
			out.add(Map.entry(new BlockPos(x, y, z), moss));
			out.add(Map.entry(new BlockPos(x, y, z + 1), Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true)));
			if (i % 4 == 2) {
				BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
				out.add(Map.entry(new BlockPos(x - 1, y, z), leaf));
				out.add(Map.entry(new BlockPos(x + 1, y, z), leaf));
				out.add(Map.entry(new BlockPos(x, y, z - 1), leaf));
			}
		} else if (i == s.height) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.abs(dx) + Math.abs(dz) <= 3) {
						out.add(Map.entry(new BlockPos(x + dx, y, z + dz), moss));
					}
				}
			}
		} else {
			out.add(Map.entry(new BlockPos(x, y, z), Blocks.CHEST.defaultBlockState()));
		}
		return out;
	}

	private void grow(ServerLevel level, Stalk s) {
		int i = s.index;
		if (!level.hasChunkAt(s.base)) {
			return; // wait until the chunk is loaded again
		}
		List<Map.Entry<BlockPos, BlockState>> blocks = layer(s, i);
		boolean trunkOk = true;
		for (Map.Entry<BlockPos, BlockState> e : blocks) {
			if (!level.hasChunkAt(e.getKey())) {
				return;
			}
		}
		if (i <= s.height) {
			BlockPos trunk = new BlockPos(s.base.getX(), s.base.getY() + i, s.base.getZ());
			trunkOk = free(level, trunk) || (i == 0 && level.getBlockState(trunk).is(Blocks.SHORT_GRASS))
					|| (i == 0 && level.getBlockState(trunk).is(Blocks.TALL_GRASS));
		}
		if (!trunkOk) {
			if (i >= minHeight / 2) {
				// blocked high up: finish here with the platform
				s.index = Math.max(0, i);
				finishAt(level, s, i);
			} else {
				s.phase = 3; // abort: take back what was placed
				s.index = i;
				dirty = true;
			}
			return;
		}
		int placed = 0;
		for (Map.Entry<BlockPos, BlockState> e : blocks) {
			if (free(level, e.getKey()) || (i == 0 && level.getBlockState(e.getKey()).getBlock() == Blocks.TALL_GRASS)) {
				level.setBlock(e.getKey(), e.getValue(), 3);
				placed++;
			}
		}
		BlockPos at = new BlockPos(s.base.getX(), s.base.getY() + i, s.base.getZ());
		burst(level, at, "minecraft:happy_villager", 3);
		if (i % 5 == 0) {
			sound(level, at, "minecraft:block.bamboo.place", 1.0f, 0.7f + (float) i / Math.max(1, s.height));
		}
		if (i > s.height) {
			complete(level, s, at);
			return;
		}
		s.index = i + 1;
		dirty = true;
	}

	private void finishAt(ServerLevel level, Stalk s, int i) {
		// Rebuild as a shorter stalk: the platform goes on the current level.
		Stalk shorter = new Stalk(s.dim, s.base, i);
		shorter.index = i;
		int idx = stalks.indexOf(s);
		shorter.phase = 0;
		if (idx >= 0) {
			stalks.set(idx, shorter);
		}
		dirty = true;
	}

	private void complete(ServerLevel level, Stalk s, BlockPos chestPos) {
		s.phase = 1;
		s.index = s.height + 1;
		if (!s.treasure) {
			s.treasure = true;
			BlockEntity be = level.getBlockEntity(chestPos);
			if (!(be instanceof Container) && level.getBlockState(chestPos).getBlock() != Blocks.CHEST) {
				// The chest cell was blocked: drop the treasure on the platform instead of losing it.
				for (ItemStack st : engine.rollStacks("beanstalk:treasure", treasureRolls)) {
					ItemEntity drop = new ItemEntity(level, chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5, st);
					level.addFreshEntity(drop);
				}
			} else if (be instanceof Container chest) {
				List<ItemStack> stacks = engine.rollStacks("beanstalk:treasure", treasureRolls);
				List<Integer> slots = new ArrayList<>();
				for (int k = 0; k < chest.getContainerSize(); k++) {
					slots.add(k);
				}
				Collections.shuffle(slots);
				for (int k = 0; k < stacks.size() && k < slots.size(); k++) {
					chest.setItem(slots.get(k), stacks.get(k));
				}
				be.setChanged();
			}
		}
		long now = level.getServer().overworld().getGameTime();
		s.expire = lifetimeMinutes > 0 ? now + lifetimeMinutes * 1200L : 0L;
		burst(level, chestPos, "minecraft:firework", 40);
		burst(level, chestPos, "minecraft:end_rod", 30);
		sound(level, chestPos, "minecraft:entity.firework_rocket.twinkle", 2.0f, 1.0f);
		sound(level, chestPos, "minecraft:entity.player.levelup", 1.5f, 0.8f);
		dirty = true;
	}

	/** Removes layers from the top down. Only blocks that still match what was placed are removed. */
	private void wither(ServerLevel level, Stalk s) {
		if (!level.hasChunkAt(s.base)) {
			return;
		}
		for (int n = 0; n < 2; n++) {
			int i = s.index;
			if (i < 0) {
				s.phase = 4;
				dirty = true;
				return;
			}
			boolean keep = false;
			for (Map.Entry<BlockPos, BlockState> e : layer(s, i)) {
				if (!level.hasChunkAt(e.getKey())) {
					return;
				}
				BlockState now = level.getBlockState(e.getKey());
				if (now.getBlock() == Blocks.CHEST) {
					BlockEntity be = level.getBlockEntity(e.getKey());
					if (be instanceof Container c && !c.isEmpty()) {
						keep = true; // never delete unclaimed treasure
					} else {
						level.setBlock(e.getKey(), Blocks.AIR.defaultBlockState(), 3);
					}
				} else if (now.getBlock() == e.getValue().getBlock()) {
					level.setBlock(e.getKey(), Blocks.AIR.defaultBlockState(), 3);
				}
			}
			if (keep && i >= s.height + 1) {
				// leave the platform under an unclaimed chest; stop withering
				s.phase = 4;
				dirty = true;
				return;
			}
			if (i % 6 == 0) {
				burst(level, new BlockPos(s.base.getX(), s.base.getY() + i, s.base.getZ()), "minecraft:composter", 4);
			}
			s.index = i - 1;
			dirty = true;
		}
	}

	// ------------------------------------------------------------------ effects

	private static void burst(ServerLevel level, BlockPos pos, String particle, int count) {
		ParticleOptions p = Ids.particle(particle);
		if (p != null) {
			level.sendParticles(p, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, count, 0.6, 0.6, 0.6, 0.05);
		}
	}

	private static void sound(ServerLevel level, BlockPos pos, String id, float vol, float pitch) {
		SoundEvent s = Ids.sound(id);
		if (s != null) {
			level.playSound((Player) null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s, SoundSource.BLOCKS, vol, pitch);
		}
	}
}
