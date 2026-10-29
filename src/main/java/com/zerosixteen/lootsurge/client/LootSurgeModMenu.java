package com.zerosixteen.lootsurge.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu entrypoint: adds the config button on Loot Surge's page in the Mods list. Only loaded if Mod Menu is installed. */
public final class LootSurgeModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new LootSurgeOptionsScreen(parent);
	}
}
