package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Keeps a Unique shulker box a Unique after it has been placed and broken again. Vanilla's shulker loot table does not
 * copy custom item data, so the placed box is remembered by position; when the block is gone, the freshly dropped
 * shulker item next to that position gets its Unique mark back. The dropped item (with its contents) is only ever
 * edited, never created or removed, so nothing can be duplicated or lost. The records live in
 * config/lootsurge/data/placed_shulkers.txt.
 *
 * <p>Known limits: a box destroyed in a way that spawns no item entity (void, lava) has no item to mark; a box moved
 * by a piston is re-found one block away; blocks removed by other mods without dropping an item keep no identity.
 */
final class ShulkerIdentity {
	private record Placed(ResourceKey<Level> dim, BlockPos pos, String id, String item) {
	}

	private record Pending(ServerLevel level, BlockPos pos, String id, String item, long expire) {
	}

	private final UniquePowers uniques;
	private final Logger log;
	private final Path file;
	private final Map<String, Placed> placed = new HashMap<>();
	private final Map<String, Integer> missing = new HashMap<>();
	private final List<Pending> pending = new ArrayList<>();
	private boolean dirty;
	private long tick;

	ShulkerIdentity(UniquePowers uniques, Path configDir, Logger log) {
		this.uniques = uniques;
		this.log = log;
		this.file = configDir.resolve("data").resolve("placed_shulkers.txt");
	}

	private static String key(ResourceKey<Level> dim, BlockPos pos) {
		return dim.identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
	}

	private static boolean isShulkerItem(ItemStack s) {
		return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("shulker_box");
	}

	void load() {
		placed.clear();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] p = line.split("\\|");
				if (p.length != 6) {
					continue;
				}
				Identifier dimId = Ids.parse(p[0]);
				if (dimId == null) {
					continue;
				}
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
				BlockPos pos = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
				placed.put(key(dim, pos), new Placed(dim, pos, p[4], p[5]));
			}
		} catch (IOException | RuntimeException e) {
			log.warn("[LootSurge] Could not read {} ({}). Placed Unique shulkers may lose their mark.", file, e.getMessage());
		}
	}

	void save() {
		if (!dirty) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			List<String> lines = new ArrayList<>();
			for (Placed p : placed.values()) {
				lines.add(p.dim().identifier() + "|" + p.pos().getX() + "|" + p.pos().getY() + "|" + p.pos().getZ() + "|" + p.id()
						+ "|" + p.item());
			}
			Path tmp = file.resolveSibling("placed_shulkers.tmp");
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (IOException e) {
			log.warn("[LootSurge] Could not save {} ({}).", file, e.getMessage());
		}
	}

	/** UseBlockCallback: remembers where a Unique shulker box is about to be placed. Never changes the click result. */
	InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		try {
			if (!(player instanceof ServerPlayer) || !(level instanceof ServerLevel sl)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = player.getItemInHand(hand);
			if (!isShulkerItem(stack) || !stack.has(DataComponents.CUSTOM_DATA)) {
				return InteractionResult.PASS;
			}
			UniquePowers.Held held = uniques.inspect(stack);
			if (held == null) {
				return InteractionResult.PASS;
			}
			BlockPos target = hit.getBlockPos();
			BlockState clicked = sl.getBlockState(target);
			if (!clicked.canBeReplaced()) {
				target = target.relative(hit.getDirection());
			}
			if (!(sl.getBlockState(target).getBlock() instanceof ShulkerBoxBlock)) {
				pending.add(new Pending(sl, target.immutable(), held.id(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
						tick + 10));
			}
		} catch (RuntimeException e) {
			log.error("[LootSurge] Error while tracking a Unique shulker placement.", e);
		}
		return InteractionResult.PASS;
	}

	/** END_SERVER_TICK. */
	void tick(MinecraftServer server) {
		tick++;
		if (!pending.isEmpty() && tick % 2 == 0) {
			Iterator<Pending> it = pending.iterator();
			while (it.hasNext()) {
				Pending p = it.next();
				if (p.level().getBlockState(p.pos()).getBlock() instanceof ShulkerBoxBlock) {
					placed.put(key(p.level().dimension(), p.pos()), new Placed(p.level().dimension(), p.pos(), p.id(), p.item()));
					dirty = true;
					it.remove();
				} else if (tick >= p.expire()) {
					it.remove();
				}
			}
		}
		if (tick % 5 == 0 && !placed.isEmpty()) {
			try {
				check(server);
			} catch (RuntimeException e) {
				log.error("[LootSurge] Error while checking placed Unique shulkers.", e);
			}
		}
		if (tick % 1200 == 0) {
			save();
		}
	}

	private void check(MinecraftServer server) {
		for (Placed p : new ArrayList<>(placed.values())) {
			ServerLevel level = server.getLevel(p.dim());
			if (level == null || !level.hasChunkAt(p.pos())) {
				continue;
			}
			String k = key(p.dim(), p.pos());
			if (level.getBlockState(p.pos()).getBlock() instanceof ShulkerBoxBlock) {
				missing.remove(k);
				continue;
			}
			if (markDrop(level, p)) {
				placed.remove(k);
				missing.remove(k);
				dirty = true;
				continue;
			}
			int n = missing.merge(k, 1, Integer::sum);
			if (n >= 8) {
				placed.remove(k);
				missing.remove(k);
				dirty = true;
				for (Direction d : Direction.values()) {
					BlockPos moved = p.pos().relative(d);
					String mk = key(p.dim(), moved);
					if (!placed.containsKey(mk) && level.getBlockState(moved).getBlock() instanceof ShulkerBoxBlock) {
						placed.put(mk, new Placed(p.dim(), moved, p.id(), p.item()));
						break;
					}
				}
			}
		}
	}

	private boolean markDrop(ServerLevel level, Placed p) {
		AABB box = new AABB(p.pos()).inflate(2.5);
		ItemEntity best = null;
		for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
			ItemStack st = ie.getItem();
			if (!isShulkerItem(st) || ie.getAge() > 200) {
				continue;
			}
			CustomData data = st.get(DataComponents.CUSTOM_DATA);
			if (data != null && data.copyTag().keySet().stream().anyMatch(s -> s.startsWith(UniquePowers.KEY_PREFIX))) {
				continue;
			}
			boolean same = BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(p.item());
			if (same) {
				best = ie;
				break;
			}
		}
		if (best == null) {
			return false;
		}
		ItemStack stack = best.getItem();
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(UniquePowers.KEY_PREFIX + p.id(), true));
		Visuals.apply(stack, p.id());
		best.setItem(stack);
		return true;
	}

	/** PlayerBlockBreakEvents.AFTER: the drop has just spawned, mark it before anyone can pick it up. */
	void onBreak(ServerLevel level, BlockPos pos) {
		if (placed.isEmpty()) {
			return;
		}
		try {
			String k = key(level.dimension(), pos);
			Placed p = placed.get(k);
			if (p != null && markDrop(level, p)) {
				placed.remove(k);
				missing.remove(k);
				dirty = true;
			}
		} catch (RuntimeException e) {
			log.error("[LootSurge] Error while marking a broken Unique shulker.", e);
		}
	}

	void onStop() {
		save();
		pending.clear();
		missing.clear();
	}
}
