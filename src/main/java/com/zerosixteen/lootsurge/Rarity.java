package com.zerosixteen.lootsurge;

import net.minecraft.core.component.DataComponents;
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
}
