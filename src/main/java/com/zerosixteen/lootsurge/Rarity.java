package com.zerosixteen.lootsurge;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Stores a drop's tier inside the item stack itself (custom data), so the floating name and light pillar can be
 * restored whenever the stack becomes a dropped item again: after a player throws it, a chest is broken, or the
 * world is reloaded. Only the stack carries the tier, so it works the same in single player and multiplayer.
 */
final class Rarity {
	static final String KEY = "lootsurge_tier";

	private Rarity() {
	}

	static void tag(ItemStack stack, Tier tier) {
		if (stack.isEmpty() || tier == null) {
			return;
		}
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(KEY, tier.key()));
	}

	/** The tier saved in the stack, or null for ordinary items. */
	static Tier read(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		return Tier.fromKey(data.copyTag().getStringOr(KEY, ""));
	}

	/** Entity tag prefix: ordinary drops remember their tier on the dropped item ENTITY, never on the stack. */
	static final String TAG_PREFIX = "lootsurge_tier:";

	/**
	 * True when the stack is exactly what vanilla would hand out for this item (no name, enchantments, damage or
	 * other components). Such stacks must stay free of custom data so they merge with every vanilla stack.
	 */
	static boolean isPlain(ItemStack stack) {
		return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem(), stack.getCount()));
	}

	static void tagEntity(ItemEntity drop, Tier tier) {
		for (String t : new java.util.ArrayList<>(drop.entityTags())) {
			if (t.startsWith(TAG_PREFIX)) {
				drop.removeTag(t);
			}
		}
		drop.addTag(TAG_PREFIX + tier.key());
	}

	static Tier readEntity(ItemEntity drop) {
		for (String t : drop.entityTags()) {
			if (t.startsWith(TAG_PREFIX)) {
				return Tier.fromKey(t.substring(TAG_PREFIX.length()));
			}
		}
		return null;
	}

	/**
	 * Heals stacks created by older versions: if the only thing separating a stack from a vanilla one is the
	 * rarity key, the key is removed so it stacks with vanilla items again. Returns the tier that was stored, if any.
	 */
	static Tier stripIfPlain(ItemStack stack) {
		Tier tier = read(stack);
		if (tier == null) {
			return null;
		}
		ItemStack probe = stack.copy();
		CompoundTag tag = probe.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.remove(KEY);
		if (tag.isEmpty()) {
			probe.remove(DataComponents.CUSTOM_DATA);
		} else {
			probe.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		}
		if (isPlain(probe)) {
			stack.remove(DataComponents.CUSTOM_DATA);
			return tier;
		}
		return null;
	}
}
