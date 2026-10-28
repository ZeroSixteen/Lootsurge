package com.zerosixteen.lootsurge;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Safe lookups of registry things by text id. Every method returns null instead of throwing. */
final class Ids {
	private Ids() {
	}

	static Identifier parse(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) {
			return null;
		}
		try {
			return Identifier.parse(s);
		} catch (RuntimeException e) {
			return null;
		}
	}

	static SoundEvent sound(String raw) {
		Identifier id = parse(raw);
		return id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
	}

	/** Only particles that need no extra data (end_rod, firework, flame, ...) can be used. */
	static ParticleOptions particle(String raw) {
		Identifier id = parse(raw);
		if (id == null) {
			return null;
		}
		Object type = BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
		return type instanceof ParticleOptions options ? options : null;
	}

	static Item item(String raw) {
		Identifier id = parse(raw);
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == null || item == Items.AIR ? null : item;
	}

	static boolean entityTypeExists(String raw) {
		Identifier id = parse(raw);
		return id != null && BuiltInRegistries.ENTITY_TYPE.getOptional(id).isPresent();
	}

	static ChatFormatting color(String name) {
		if (name == null) {
			return ChatFormatting.WHITE;
		}
		return switch (name.trim().toLowerCase(Locale.ROOT)) {
			case "gray", "grey" -> ChatFormatting.GRAY;
			case "dark_gray", "dark_grey" -> ChatFormatting.DARK_GRAY;
			case "green" -> ChatFormatting.GREEN;
			case "dark_green" -> ChatFormatting.DARK_GREEN;
			case "aqua" -> ChatFormatting.AQUA;
			case "dark_aqua" -> ChatFormatting.DARK_AQUA;
			case "blue" -> ChatFormatting.BLUE;
			case "dark_blue" -> ChatFormatting.DARK_BLUE;
			case "light_purple", "pink" -> ChatFormatting.LIGHT_PURPLE;
			case "dark_purple", "purple" -> ChatFormatting.DARK_PURPLE;
			case "red" -> ChatFormatting.RED;
			case "dark_red" -> ChatFormatting.DARK_RED;
			case "gold", "orange" -> ChatFormatting.GOLD;
			case "yellow" -> ChatFormatting.YELLOW;
			default -> ChatFormatting.WHITE;
		};
	}
}
