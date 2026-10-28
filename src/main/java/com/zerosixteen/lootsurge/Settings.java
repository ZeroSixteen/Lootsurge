package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

/** The validated contents of config.json. */
final class Settings {
	static final String DEFAULT_CONFIG_RESOURCE = "/lootsurge/defaults/config.json";

	boolean enabled = true;
	boolean requirePlayerKill = true;
	boolean skipBabyMobs = true;
	boolean fallbackMonstersOnly = true;
	boolean debugLogging = false;
	double globalChanceMultiplier = 1.0;
	int maxActivePillars = 8;
	Set<String> excludedEntities = new HashSet<>();
	final EnumMap<Tier, TierSettings> tiers = new EnumMap<>(Tier.class);

	static Settings load(Path file, Logger log) {
		JsonObject base = readResource(DEFAULT_CONFIG_RESOURCE, log);
		JsonObject user = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (parsed.isJsonObject()) {
					user = parsed.getAsJsonObject();
				} else {
					log.error("[LootSurge] {} is not a JSON object. Using built-in defaults instead.", file);
				}
			} catch (IOException | RuntimeException e) {
				log.error("[LootSurge] Could not read {} ({}). Using built-in defaults instead.", file, e.getMessage());
			}
		}

		Layered root = new Layered(user, base);
		Settings s = new Settings();
		s.enabled = root.bool("enabled", true);
		s.requirePlayerKill = root.bool("requirePlayerKill", true);
		s.skipBabyMobs = root.bool("skipBabyMobs", true);
		s.fallbackMonstersOnly = root.bool("fallbackMonstersOnly", true);
		s.debugLogging = root.bool("debugLogging", false);
		s.globalChanceMultiplier = Layered.clamp(root.number("globalChanceMultiplier", 1.0), 0.0, 100.0);
		s.maxActivePillars = Layered.clamp(root.integer("maxActivePillars", 8), 0, 64);

		JsonArray excluded = root.array("excludedEntities");
		if (excluded != null) {
			for (JsonElement e : excluded) {
				if (e.isJsonPrimitive()) {
					s.excludedEntities.add(e.getAsString().trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		s.excludedEntities.add("minecraft:player");

		Layered tierRoot = root.child("tiers");
		for (Tier t : Tier.values()) {
			s.tiers.put(t, TierSettings.parse(t, tierRoot.child(t.key()), log));
		}
		return s;
	}

	TierSettings tier(Tier t) {
		return tiers.get(t);
	}

	static JsonObject readResource(String path, Logger log) {
		try (InputStream in = Settings.class.getResourceAsStream(path)) {
			if (in == null) {
				log.error("[LootSurge] Bundled resource {} is missing from the jar!", path);
				return null;
			}
			JsonElement e = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (IOException | RuntimeException ex) {
			log.error("[LootSurge] Could not read bundled resource {}: {}", path, ex.getMessage());
			return null;
		}
	}
}
