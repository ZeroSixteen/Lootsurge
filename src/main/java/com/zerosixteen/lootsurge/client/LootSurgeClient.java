package com.zerosixteen.lootsurge.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.zerosixteen.lootsurge.LootSurgeMod;
import com.zerosixteen.lootsurge.PowerKeyPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Client side: the "Use Unique Power" key (Options > Controls > Loot Surge). Unique items whose normal right-click
 * already does something (bows, armor, elytra, spyglass...) use this key for their power.
 */
public final class LootSurgeClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category
			.register(Identifier.fromNamespaceAndPath(LootSurgeMod.MOD_ID, "keys"));
	public static final KeyMapping POWER_KEY = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.lootsurge.power", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (POWER_KEY.consumeClick()) {
				if (client.getConnection() != null && ClientPlayNetworking.canSend(PowerKeyPayload.TYPE)) {
					ClientPlayNetworking.send(PowerKeyPayload.INSTANCE);
				}
			}
		});
	}
}
