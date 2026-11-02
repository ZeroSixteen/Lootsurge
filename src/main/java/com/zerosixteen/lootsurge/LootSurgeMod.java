package com.zerosixteen.lootsurge;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
		UniquePowers uniques = new UniquePowers();
		LootEngine engine = new LootEngine(LOGGER, configDir, effects, uniques);
		Activities activities = new Activities(LOGGER, configDir, engine, uniques);
		engine.setActivities(activities);
		activities.register();

		// Expansion features: Giant Beanstalk, Woolbound Dream, Unique shulker identity.
		Beanstalk beanstalk = new Beanstalk(LOGGER, configDir, engine);
		Woolbound woolbound = new Woolbound(uniques);
		ShulkerIdentity shulkers = new ShulkerIdentity(uniques, configDir, LOGGER);
		engine.setAfterReload(beanstalk::reload);

		// Pools need the server's registries (enchantments), so everything is (re)loaded once a server exists.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			engine.reload(server);
			beanstalk.load();
			shulkers.load();
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				engine.reload(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			effects.clear();
			uniques.clear();
			activities.onStop();
			beanstalk.onStop();
			shulkers.onStop();
			woolbound.clear();
		});
		// Item entities: restore rarity labels/pillars on re-dropped items; eggs count as an activity; Hiro cleanup.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			engine.onEntityLoad(entity, level);
			activities.onEntityLoad(entity, level);
			uniques.companion().onEntityLoad(entity);
			uniques.onEntityLoad(entity);
		});

		// Power key: the client sends an empty packet, the server runs the Unique power of whatever the player holds or wears.
		PayloadTypeRegistry.serverboundPlay().register(PowerKeyPayload.TYPE, PowerKeyPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PowerKeyPayload.TYPE,
				(payload, context) -> uniques.usePower(context.player()));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			engine.healInventory(handler.getPlayer());
			woolbound.onJoin(handler.getPlayer());
		});
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> woolbound.onCopy(oldPlayer, newPlayer));
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (level instanceof ServerLevel sl) {
				shulkers.onBreak(sl, pos);
				if (player instanceof ServerPlayer sp) {
					beanstalk.onBreak(sl, sp, pos, state);
				}
			}
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			InteractionResult dream = woolbound.use(player, level, hand);
			if (dream != InteractionResult.PASS) {
				return dream;
			}
			return shulkers.onUseBlock(player, level, hand, hit);
		});

		// /lootsurge commands and the creative tab with every Unique.
		LootSurgeCommands commands = new LootSurgeCommands(engine);
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> commands.register(dispatcher));
		UniqueTab.register();

		// Fires once per living entity death, after vanilla has already handled its own drops.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			engine.onDeath(entity, source);
			activities.onDeath(entity, source);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			effects.tick();
			uniques.tick(server);
			activities.tick(server);
			beanstalk.tick(server);
			woolbound.tick(server);
			shulkers.tick(server);
			if (ConfigFile.RELOAD_REQUESTED.getAndSet(false)) {
				engine.reload(server);
			}
		});

		// Unique item powers: immunities, on-hit effects, and right-click fireballs.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(uniques::allowDamage);
		ServerLivingEntityEvents.ALLOW_DEATH.register(uniques::allowDeath);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(uniques::afterDamage);
		UseItemCallback.EVENT.register((player, level, hand) -> {
			InteractionResult dream = woolbound.use(player, level, hand);
			return dream != InteractionResult.PASS ? dream : uniques.onUse(player, level, hand);
		});

		LOGGER.info("Loot Surge by {} loaded. Config folder: {}", CREATOR, configDir);
	}
}
