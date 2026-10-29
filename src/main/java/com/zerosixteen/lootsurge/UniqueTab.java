package com.zerosixteen.lootsurge;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Registers the "Loot Surge" creative tab that holds every bundled Unique. The contents are built on the client. */
final class UniqueTab {
	private UniqueTab() {
	}

	static void register() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(LootSurgeMod.MOD_ID, "uniques"),
				FabricCreativeModeTab.builder()
						.title(Component.literal("Loot Surge Uniques").withStyle(ChatFormatting.GOLD))
						.icon(() -> new ItemStack(Ids.item("minecraft:nether_star")))
						.displayItems((context, output) -> UniqueTabContents.fill(output))
						.build());
	}
}
