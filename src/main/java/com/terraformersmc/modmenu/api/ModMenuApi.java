package com.terraformersmc.modmenu.api;

/** Compile-time copy of Mod Menu's entrypoint interface. Excluded from the built jar. */
public interface ModMenuApi {
	default ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> null;
	}
}
