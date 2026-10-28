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
