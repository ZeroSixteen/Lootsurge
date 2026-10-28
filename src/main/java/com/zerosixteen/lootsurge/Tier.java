package com.zerosixteen.lootsurge;

import java.util.Locale;

/** The six drop tiers, lowest to highest. The config keys are the lower-case names. */
enum Tier {
	COMMON,
	UNCOMMON,
	RARE,
	EPIC,
	LEGENDARY,
	MYTHIC;

	String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The next tier down, or null for COMMON. */
	Tier lower() {
		return ordinal() == 0 ? null : values()[ordinal() - 1];
	}

	static Tier fromKey(String key) {
		if (key == null) {
			return null;
		}
		String k = key.trim().toLowerCase(Locale.ROOT);
		for (Tier t : values()) {
			if (t.key().equals(k)) {
				return t;
			}
		}
		return null;
	}
}
