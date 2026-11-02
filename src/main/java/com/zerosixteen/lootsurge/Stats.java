package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Unique item stats. A Unique's "powers.params" may contain: attackDamage, attackSpeed, reach (entity reach),
 * blockReach, armor, toughness, maxHealth, moveSpeed (percent, 0.1 = +10%), knockbackResist (0-1), knockback, mining
 * (mining efficiency), stepHeight. They become real attribute modifiers on the stack, kept next to the item's vanilla ones.
 *
 * <p>The modifiers are reapplied whenever a Unique is looked at (held, worn, picked up), so items that already exist
 * receive new or buffed stats without being re-rolled. Modifier ids all start with "lootsurge:unique_".
 */
final class Stats {
	private static final String NS = "lootsurge";
	private static final String PREFIX = "unique_";

	private record Spec(String key, Holder<Attribute> attribute, AttributeModifier.Operation op, boolean armorOnly) {
		Identifier id() {
			return Identifier.fromNamespaceAndPath(NS, PREFIX + key);
		}
	}

	private static final List<Spec> SPECS = List.of(
			new Spec("attackDamage", Attributes.ATTACK_DAMAGE, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("attackSpeed", Attributes.ATTACK_SPEED, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("reach", Attributes.ENTITY_INTERACTION_RANGE, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("blockReach", Attributes.BLOCK_INTERACTION_RANGE, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("knockback", Attributes.ATTACK_KNOCKBACK, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("mining", Attributes.MINING_EFFICIENCY, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("armor", Attributes.ARMOR, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("toughness", Attributes.ARMOR_TOUGHNESS, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("maxHealth", Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("moveSpeed", Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, false),
			new Spec("knockbackResist", Attributes.KNOCKBACK_RESISTANCE, AttributeModifier.Operation.ADD_VALUE, false),
			new Spec("stepHeight", Attributes.STEP_HEIGHT, AttributeModifier.Operation.ADD_VALUE, false));

	private Stats() {
	}

	/** Makes the stack's attribute modifiers match the Unique's params. Cheap when nothing changed. */
	static void apply(ItemStack stack, UniquePowers.Powers powers) {
		if (stack == null || stack.isEmpty() || powers == null) {
			return;
		}
		EquipmentSlotGroup group = EquipmentSlotGroup.MAINHAND;
		Equippable eq = stack.get(DataComponents.EQUIPPABLE);
		if (eq != null) {
			group = switch (eq.slot()) {
				case HEAD -> EquipmentSlotGroup.HEAD;
				case CHEST -> EquipmentSlotGroup.CHEST;
				case LEGS -> EquipmentSlotGroup.LEGS;
				case FEET -> EquipmentSlotGroup.FEET;
				case OFFHAND -> EquipmentSlotGroup.OFFHAND;
				default -> EquipmentSlotGroup.MAINHAND;
			};
		}
		Map<Spec, Double> want = new LinkedHashMap<>();
		for (Spec s : SPECS) {
			double v = powers.param(s.key(), 0.0);
			if (v != 0.0) {
				want.put(s, v);
			}
		}
		ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		int found = 0;
		boolean same = true;
		for (ItemAttributeModifiers.Entry e : current.modifiers()) {
			Identifier id = e.modifier().id();
			if (!NS.equals(id.getNamespace()) || !id.getPath().startsWith(PREFIX)) {
				continue;
			}
			found++;
			boolean ok = false;
			for (Map.Entry<Spec, Double> w : want.entrySet()) {
				if (w.getKey().id().equals(id) && Math.abs(e.modifier().amount() - w.getValue()) < 1.0e-6
						&& e.slot() == group) {
					ok = true;
					break;
				}
			}
			same &= ok;
		}
		if (same && found == want.size()) {
			return;
		}
		ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
		List<ItemAttributeModifiers.Entry> keep = new ArrayList<>();
		for (ItemAttributeModifiers.Entry e : current.modifiers()) {
			Identifier id = e.modifier().id();
			if (!(NS.equals(id.getNamespace()) && id.getPath().startsWith(PREFIX))) {
				keep.add(e);
			}
		}
		for (ItemAttributeModifiers.Entry e : keep) {
			b.add(e.attribute(), e.modifier(), e.slot());
		}
		for (Map.Entry<Spec, Double> w : want.entrySet()) {
			Spec s = w.getKey();
			b.add(s.attribute(), new AttributeModifier(s.id(), w.getValue(), s.op()), group);
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, b.build());
	}
}
