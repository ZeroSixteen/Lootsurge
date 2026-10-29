package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Small helper used by the options screen to change single top-level keys of config.json. */
public final class ConfigFile {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Set by the options screen; the server tick reloads the config when it sees this. */
	public static final AtomicBoolean RELOAD_REQUESTED = new AtomicBoolean(false);

	private ConfigFile() {
	}

	public static Path path() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(LootSurgeMod.MOD_ID)
				.resolve("config.json");
	}

	/** The current validated settings, read from disk (defaults fill any gaps). */
	public static Settings read() {
		return Settings.load(path(), LootSurgeMod.LOGGER);
	}

	public static synchronized void set(String key, Object value) {
		Path file = path();
		JsonObject root = new JsonObject();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (parsed.isJsonObject()) {
					root = parsed.getAsJsonObject();
				}
			} catch (IOException | RuntimeException e) {
				LootSurgeMod.LOGGER.error("[LootSurge] Could not read {} ({}); it will be rewritten.", file, e.getMessage());
			}
		}
		if (value instanceof Boolean b) {
			root.addProperty(key, b);
		} else if (value instanceof Number n) {
			root.addProperty(key, n);
		} else {
			root.addProperty(key, String.valueOf(value));
		}
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			RELOAD_REQUESTED.set(true);
		} catch (IOException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Could not save {}: {}", file, e.getMessage());
		}
	}
}
