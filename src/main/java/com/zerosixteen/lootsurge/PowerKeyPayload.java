package com.zerosixteen.lootsurge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent by the client when the "Use Unique Power" key is pressed. Carries no data; the server decides what it does. */
public final class PowerKeyPayload implements CustomPacketPayload {
	public static final PowerKeyPayload INSTANCE = new PowerKeyPayload();
	public static final CustomPacketPayload.Type<PowerKeyPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(LootSurgeMod.MOD_ID, "power_key"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PowerKeyPayload> CODEC = StreamCodec.unit(INSTANCE);

	private PowerKeyPayload() {
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
