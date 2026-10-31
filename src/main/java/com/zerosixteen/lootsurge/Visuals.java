package com.zerosixteen.lootsurge;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Points each Unique at its own item model (and worn armor model): the stack gets the item_model component
 * "lootsurge:<id>" and, for armor, an equippable asset "lootsurge:<id>". The jar bundles plain vanilla-looking
 * fallbacks for those ids and the Loot Surge Visuals resource pack replaces them with the custom art.
 * Purely visual: no power, stat, name or drop chance is touched. Switch off in uniques/activities.json (visuals).
 */
final class Visuals {
	static volatile boolean enabled = true;

	private Visuals() {
	}

	static void apply(ItemStack stack, String uniqueId) {
		if (!enabled || stack == null || stack.isEmpty() || uniqueId == null) {
			return;
		}
		Identifier id = Identifier.fromNamespaceAndPath(LootSurgeMod.MOD_ID, uniqueId);
		if (!id.equals(stack.get(DataComponents.ITEM_MODEL))) {
			stack.set(DataComponents.ITEM_MODEL, id);
		}
		Equippable eq = stack.get(DataComponents.EQUIPPABLE);
		if (eq != null && isWearable(eq.slot())) {
			ResourceKey<EquipmentAsset> key = ResourceKey.create(EquipmentAssets.ROOT_ID, id);
			if (!eq.assetId().isPresent() || !eq.assetId().get().equals(key)) {
				stack.set(DataComponents.EQUIPPABLE, Equippable.builder(eq.slot()).setAsset(key)
						.setEquipSound(eq.equipSound()).setDamageOnHurt(eq.damageOnHurt()).build());
			}
		}
	}

	private static boolean isWearable(EquipmentSlot slot) {
		return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS
				|| slot == EquipmentSlot.FEET;
	}
}
