package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;

/** Copies the bundled default config and pools into config/lootsurge/ the first time the mod runs. */
final class Defaults {
	private static final String ROOT = "/lootsurge/defaults/";

	private Defaults() {
	}

	static void install(Path configDir, Logger log) {
		try {
			Files.createDirectories(configDir);

			Path config = configDir.resolve("config.json");
			if (!Files.exists(config)) {
				copy("config.json", config, log);
			}

			Path poolsDir = configDir.resolve("pools");
			if (!Files.exists(poolsDir)) {
				Files.createDirectories(poolsDir);
				String index = readText(ROOT + "pools/_index.txt", log);
				if (index != null) {
					for (String line : index.split("\\R")) {
						String name = line.trim();
						if (!name.isEmpty() && !name.startsWith("#")) {
							copy("pools/" + name, poolsDir.resolve(name), log);
						}
					}
				}
			}

			// New in the expansion: activity Uniques. Only installed when the folder is missing, never overwritten.
			Path uniquesDir = configDir.resolve("uniques");
			if (!Files.exists(uniquesDir)) {
				Files.createDirectories(uniquesDir);
				String index = readText(ROOT + "uniques/_index.txt", log);
				if (index != null) {
					for (String line : index.split("\\R")) {
						String name = line.trim();
						if (!name.isEmpty() && !name.startsWith("#")) {
							copy("uniques/" + name, uniquesDir.resolve(name), log);
						}
					}
				}
			}
			// Expansion: bonus-drop tables for blocks, farming, fishing, animals, bosses... Only installed when missing.
			Path sourcesDir = configDir.resolve("sources");
			if (!Files.exists(sourcesDir)) {
				Files.createDirectories(sourcesDir);
				String index = readText(ROOT + "sources/_index.txt", log);
				if (index != null) {
					for (String line : index.split("\\R")) {
						String name = line.trim();
						if (!name.isEmpty() && !name.startsWith("#")) {
							copy("sources/" + name, sourcesDir.resolve(name), log);
						}
					}
				}
			}
			// Files added by later versions: copied once into existing configs, never overwritten, never re-added after the user deletes them.
			for (String version : new String[] { "2", "3" }) {
				Path marker = configDir.resolve(".lootsurge_added_v" + version);
				if (Files.exists(marker)) {
					continue;
				}
				String added = readText(ROOT + "_added_v" + version + ".txt", log);
				if (added != null) {
					for (String line : added.split("\\R")) {
						String name = line.trim();
						if (name.isEmpty() || name.startsWith("#")) {
							continue;
						}
						Path target = configDir.resolve(name);
						if (!Files.exists(target)) {
							Files.createDirectories(target.getParent());
							copy(name, target, log);
						}
					}
				}
				Files.writeString(marker, "Loot Surge expansion files were installed. Delete this file to re-add missing ones.", StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			log.error("[LootSurge] Could not create default config files in {}: {}", configDir, e.getMessage());
		}
	}

	private static void copy(String resource, Path target, Logger log) {
		String text = readText(ROOT + resource, log);
		if (text == null) {
			return;
		}
		try {
			Files.writeString(target, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("[LootSurge] Could not write {}: {}", target, e.getMessage());
		}
	}

	/** Text of a bundled default file, e.g. "pools/_index.txt", or null. */
	static String readBundledText(String relative) {
		return readText(ROOT + relative, LootSurgeMod.LOGGER);
	}

	private static String readText(String resourcePath, Logger log) {
		try (InputStream in = Defaults.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				log.error("[LootSurge] Bundled file {} is missing from the jar.", resourcePath);
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("[LootSurge] Could not read bundled file {}: {}", resourcePath, e.getMessage());
			return null;
		}
	}
}
