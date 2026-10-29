package com.zerosixteen.lootsurge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/** Client-only helper, kept in its own class so a dedicated server never loads it. */
final class UniqueTabContents {
	private UniqueTabContents() {
	}

	static void fill(CreativeModeTab.Output output) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return;
		}
		for (ItemStack stack : UniqueCatalog.bundledShowcase(mc.level.registryAccess())) {
			output.accept(stack);
		}
	}
}
