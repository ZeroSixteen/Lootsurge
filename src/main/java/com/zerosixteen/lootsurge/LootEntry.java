package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/** One possible bonus item inside a pool. Immutable after loading. */
final class LootEntry {
	record EnchantSpec(Holder<Enchantment> holder, int minLevel, int maxLevel, double chance) {
	}

	final Item item;
	final Set<Tier> tiers;
	final double weight;
	final int minCount;
	final int maxCount;
	final List<EnchantSpec> enchants;
	final boolean pickOne;
	final int maxEnchants;
	final double minDamage;
	final double maxDamage;

	LootEntry(Item item, Set<Tier> tiers, double weight, int minCount, int maxCount, List<EnchantSpec> enchants,
			boolean pickOne, int maxEnchants, double minDamage, double maxDamage) {
		this.item = item;
		this.tiers = EnumSet.copyOf(tiers);
		this.weight = weight;
		this.minCount = minCount;
		this.maxCount = maxCount;
		this.enchants = List.copyOf(enchants);
		this.pickOne = pickOne;
		this.maxEnchants = maxEnchants;
		this.minDamage = minDamage;
		this.maxDamage = maxDamage;
	}

	/** Builds a fresh item stack. Enchantment levels are always clamped to the enchantment's real maximum. */
	ItemStack create(Random rnd) {
		ItemStack stack = new ItemStack(item, 1);
		int count = minCount + rnd.nextInt(maxCount - minCount + 1);
		stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));

		int durability = stack.getMaxDamage();
		if (maxDamage > 0.0 && durability > 1) {
			double fraction = minDamage + rnd.nextDouble() * (maxDamage - minDamage);
			int damage = (int) Math.floor(fraction * durability);
			stack.setDamageValue(Math.max(0, Math.min(damage, durability - 1)));
		}

		if (!enchants.isEmpty()) {
			if (pickOne) {
				apply(stack, enchants.get(rnd.nextInt(enchants.size())), rnd);
			} else {
				List<EnchantSpec> shuffled = new ArrayList<>(enchants);
				Collections.shuffle(shuffled, rnd);
				int applied = 0;
				for (EnchantSpec spec : shuffled) {
					if (applied >= maxEnchants) {
						break;
					}
					if (rnd.nextDouble() < spec.chance()) {
						apply(stack, spec, rnd);
						applied++;
					}
				}
			}
		}
		return stack;
	}

	private static void apply(ItemStack stack, EnchantSpec spec, Random rnd) {
		int vanillaMax = Math.max(1, spec.holder().value().getMaxLevel());
		int max = Math.max(1, Math.min(spec.maxLevel(), vanillaMax));
		int min = Math.max(1, Math.min(spec.minLevel(), max));
		int level = min + rnd.nextInt(max - min + 1);
		stack.enchant(spec.holder(), level);
	}
}
