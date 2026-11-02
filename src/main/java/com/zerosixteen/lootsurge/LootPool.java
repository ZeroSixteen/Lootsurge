package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

/** A mob-specific bonus loot pool. Immutable after loading. */
final class LootPool {
	final String name;
	final Set<String> entities;
	final boolean fallback;
	final double dropChance;
	private final EnumMap<Tier, Double> tierWeights;
	final List<LootEntry> entries;
	/** Activity source keys like "mining:ore", "fishing:*" or "blocks:*" (empty for plain mob pools). */
	Set<String> sources = Set.of();
	/** Per-player cooldown between rolls of this source pool, in ticks. */
	int cooldownTicks = 0;
	/** Materials/entities that never roll here (ids or plain names). */
	Set<String> excluded = Set.of();
	/** When true (sources/*.json with entities), this pool rolls on top of the mob's normal pool instead of only filling gaps. */
	boolean additional = false;

	LootPool(String name, Set<String> entities, boolean fallback, double dropChance, EnumMap<Tier, Double> tierWeights,
			List<LootEntry> entries) {
		this.name = name;
		this.entities = Set.copyOf(entities);
		this.fallback = fallback;
		this.dropChance = dropChance;
		this.tierWeights = new EnumMap<>(tierWeights);
		this.entries = List.copyOf(entries);
	}

	/** Multiplier applied to a tier's global weight for this pool (1.0 if unspecified). */
	double tierMultiplier(Tier t) {
		return tierWeights.getOrDefault(t, 1.0);
	}

	/** Entries usable for the tier; if there are none, walks down to the nearest lower tier that has some. */
	List<LootEntry> entriesFor(Tier t) {
		List<LootEntry> out = new ArrayList<>();
		for (LootEntry e : entries) {
			if (e.tiers.contains(t)) {
				out.add(e);
			}
		}
		return out;
	}
}
