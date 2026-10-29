package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * Compile-time copy of Mod Menu's API interface so Loot Surge can offer an options screen without bundling Mod Menu.
 * It is deliberately EXCLUDED from the built jar (see build.gradle); the real Mod Menu supplies it at runtime.
 */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
	S create(Screen parent);
}
