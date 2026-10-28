package com.zerosixteen.lootsurge;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loot Surge - tiered bonus mob loot with light pillars and jackpot effects.
 *
 * <p>Created by ZeroSixteen.
 */
public final class LootSurgeMod implements ModInitializer {
	public static final String MOD_ID = "lootsurge";
	public static final String CREATOR = "ZeroSixteen";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		Defaults.install(configDir, LOGGER);

		Effects effects = new Effects();
		LootEngine engine = new LootEngine(LOGGER, configDir, effects);

		// Pools need the server's registries (enchantments), so everything is (re)loaded once a server exists.
		ServerLifecycleEvents.SERVER_STARTED.register(engine::reload);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				engine.reload(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> effects.clear());

		// Fires once per living entity death, after vanilla has already handled its own drops.
		ServerLivingEntityEvents.AFTER_DEATH.register(engine::onDeath);
		ServerTickEvents.END_SERVER_TICK.register(server -> effects.tick());

		LOGGER.info("Loot Surge by {} loaded. Config folder: {}", CREATOR, configDir);
	}
}
