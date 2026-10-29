package com.zerosixteen.lootsurge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;

/** Reads every pools/*.json file. A broken file or entry is skipped with a clear log message, never a crash. */
final class PoolLoader {
	private PoolLoader() {
	}

	static List<LootPool> loadAll(Path dir, RegistryAccess registries, Logger log) {
		List<LootPool> pools = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			log.warn("[LootSurge] Pool folder {} does not exist; no bonus loot will drop.", dir);
			return pools;
		}
		List<Path> files;
		try (Stream<Path> stream = Files.list(dir)) {
			files = stream.filter(Files::isRegularFile).filter(p -> {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return n.endsWith(".json") && !n.startsWith("_");
			}).sorted().toList();
		} catch (IOException e) {
			log.error("[LootSurge] Could not list {}: {}", dir, e.getMessage());
			return pools;
		}

		HolderLookup.RegistryLookup<Enchantment> enchantLookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
		for (Path file : files) {
			String name = file.getFileName().toString();
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (!parsed.isJsonObject()) {
					log.error("[LootSurge] {} must contain a JSON object. File skipped.", name);
					continue;
				}
				LootPool pool = parsePool(name, parsed.getAsJsonObject(), enchantLookup, log);
				if (pool != null) {
					pools.add(pool);
				}
			} catch (IOException | RuntimeException e) {
				log.error("[LootSurge] {} is not valid JSON or could not be read ({}). File skipped.", name,
						e.getMessage());
			}
		}
		return pools;
	}

	private static LootPool parsePool(String file, JsonObject root, HolderLookup.RegistryLookup<Enchantment> enchantLookup,
			Logger log) {
		Layered c = new Layered(root, null);
		boolean fallback = c.bool("fallback", false);
		double dropChance = Layered.clamp(c.number("dropChance", 0.05), 0.0, 1.0);

		Set<String> entities = new HashSet<>();
		JsonArray entityArray = c.array("entities");
		if (entityArray != null) {
			for (JsonElement e : entityArray) {
				if (e.isJsonPrimitive()) {
					String id = e.getAsString().trim().toLowerCase(Locale.ROOT);
					if (!Ids.entityTypeExists(id)) {
						log.info("[LootSurge] {}: entity '{}' is not registered in this game (other mod missing?). Kept anyway.",
								file, id);
					}
					entities.add(id);
				}
			}
		}
		if (!fallback && entities.isEmpty()) {
			log.error("[LootSurge] {}: needs a non-empty \"entities\" list or \"fallback\": true. File skipped.", file);
			return null;
		}

		EnumMap<Tier, Double> tierWeights = new EnumMap<>(Tier.class);
		Layered tw = c.child("tierWeights");
		for (Tier t : Tier.values()) {
			tierWeights.put(t, Layered.clamp(tw.number(t.key(), 1.0), 0.0, 1000.0));
		}

		List<LootEntry> entries = new ArrayList<>();
		JsonArray entryArray = c.array("entries");
		if (entryArray != null) {
			int index = 0;
			for (JsonElement e : entryArray) {
				if (e.isJsonObject()) {
					LootEntry entry = parseEntry(file, index, e.getAsJsonObject(), enchantLookup, log);
					if (entry != null) {
						entries.add(entry);
					}
				}
				index++;
			}
		}
		if (entries.isEmpty()) {
			log.warn("[LootSurge] {}: no usable entries, file skipped.", file);
			return null;
		}
		return new LootPool(file.substring(0, file.length() - 5), entities, fallback, dropChance, tierWeights, entries);
	}

	private static LootEntry parseEntry(String file, int index, JsonObject o,
			HolderLookup.RegistryLookup<Enchantment> enchantLookup, Logger log) {
		String where = file + " entries[" + index + "]";
		Layered c = new Layered(o, null);

		String itemId = c.string("item", "");
		Item item = Ids.item(itemId);
		if (item == null) {
			log.warn("[LootSurge] {}: unknown item '{}', entry skipped.", where, itemId);
			return null;
		}

		double weight = Layered.clamp(c.number("weight", 1.0), 0.0, 1_000_000.0);
		if (weight <= 0.0) {
			log.warn("[LootSurge] {}: weight must be above 0, entry skipped.", where);
			return null;
		}

		Set<Tier> tiers = EnumSet.noneOf(Tier.class);
		JsonArray tierArray = c.array("tiers");
		if (tierArray == null) {
			tiers.addAll(EnumSet.allOf(Tier.class));
		} else {
			for (JsonElement e : tierArray) {
				Tier t = e.isJsonPrimitive() ? Tier.fromKey(e.getAsString()) : null;
				if (t == null) {
					log.warn("[LootSurge] {}: unknown tier {} ignored.", where, e);
				} else {
					tiers.add(t);
				}
			}
		}
		if (tiers.isEmpty()) {
			log.warn("[LootSurge] {}: no valid tiers, entry skipped.", where);
			return null;
		}

		int minCount = 1;
		int maxCount = 1;
		JsonElement countEl = o.get("count");
		if (countEl != null && countEl.isJsonPrimitive()) {
			minCount = Layered.clamp(c.integer("count", 1), 1, 64);
			maxCount = minCount;
		} else if (countEl != null && countEl.isJsonObject()) {
			Layered cc = new Layered(countEl.getAsJsonObject(), null);
			minCount = Layered.clamp(cc.integer("min", 1), 1, 64);
			maxCount = Layered.clamp(cc.integer("max", minCount), minCount, 64);
		}

		double minDamage = 0.0;
		double maxDamage = 0.0;
		JsonElement dmgEl = o.get("damage");
		if (dmgEl != null && dmgEl.isJsonObject()) {
			Layered dc = new Layered(dmgEl.getAsJsonObject(), null);
			minDamage = Layered.clamp(dc.number("min", 0.0), 0.0, 0.95);
			maxDamage = Layered.clamp(dc.number("max", minDamage), minDamage, 0.95);
		}

		List<LootEntry.EnchantSpec> specs = new ArrayList<>();
		JsonArray enchArray = c.array("enchantments");
		if (enchArray != null) {
			for (JsonElement e : enchArray) {
				LootEntry.EnchantSpec spec = parseEnchant(where, e, enchantLookup, log);
				if (spec != null) {
					specs.add(spec);
				}
			}
			if (specs.isEmpty() && !enchArray.isEmpty()) {
				log.warn("[LootSurge] {}: none of its enchantments are valid; entry skipped.", where);
				return null;
			}
		}
		boolean pickOne = c.string("pick", "all").equalsIgnoreCase("one");
		int maxEnchants = Layered.clamp(c.integer("maxEnchantments", 99), 1, 99);

		String uniqueId = c.string("unique", "").trim().toLowerCase(Locale.ROOT);
		UniquePowers.Powers powers = null;
		if (uniqueId.isEmpty()) {
			uniqueId = null;
		} else if (!uniqueId.matches("[a-z0-9_]{1,40}")) {
			log.warn("[LootSurge] {}: unique id '{}' may only use a-z, 0-9 and _ (max 40). Entry skipped.", where, uniqueId);
			return null;
		} else {
			powers = UniquePowers.parse(c.child("powers"), log, where);
		}

		Component name = null;
		String rawName = c.string("name", "");
		if (!rawName.isBlank()) {
			name = Component.literal(rawName)
					.withStyle(Ids.color(c.string("nameColor", uniqueId != null ? "orange" : "white")));
		}

		return new LootEntry(item, tiers, weight, minCount, maxCount, specs, pickOne, maxEnchants, minDamage, maxDamage,
				name, uniqueId, powers);
	}

	private static LootEntry.EnchantSpec parseEnchant(String where, JsonElement e,
			HolderLookup.RegistryLookup<Enchantment> enchantLookup, Logger log) {
		String id;
		Layered c;
		if (e.isJsonPrimitive()) {
			id = e.getAsString();
			c = new Layered(new JsonObject(), null);
		} else if (e.isJsonObject()) {
			c = new Layered(e.getAsJsonObject(), null);
			id = c.string("id", "");
		} else {
			return null;
		}
		Identifier identifier = Ids.parse(id);
		Holder<Enchantment> holder = null;
		if (identifier != null) {
			Optional<Holder.Reference<Enchantment>> found = enchantLookup
					.get(ResourceKey.create(Registries.ENCHANTMENT, identifier));
			holder = found.orElse(null);
		}
		if (holder == null) {
			log.warn("[LootSurge] {}: unknown enchantment '{}', it is skipped.", where, id);
			return null;
		}
		int min = Layered.clamp(c.integer("minLevel", 1), 1, 255);
		int max = Layered.clamp(c.integer("maxLevel", min), min, 255);
		double chance = Layered.clamp(c.number("chance", 1.0), 0.0, 1.0);
		boolean overcap = c.bool("overcap", false);
		return new LootEntry.EnchantSpec(holder, min, max, chance, overcap);
	}

	/** Used by the engine for debug output. */
	static Map<String, Integer> summarize(List<LootPool> pools) {
		Map<String, Integer> m = new java.util.TreeMap<>();
		for (LootPool p : pools) {
			m.put(p.name, p.entries.size());
		}
		return m;
	}
}
