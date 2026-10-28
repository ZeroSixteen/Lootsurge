package com.zerosixteen.lootsurge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads values from the player's JSON first, then from the bundled default JSON, then from a default in code.
 * This means a missing or deleted key in a player's config never breaks the mod.
 */
final class Layered {
	private final JsonObject user;
	private final JsonObject base;

	Layered(JsonObject user, JsonObject base) {
		this.user = user;
		this.base = base;
	}

	Layered child(String key) {
		return new Layered(object(user, key), object(base, key));
	}

	private static JsonObject object(JsonObject parent, String key) {
		JsonElement e = get(parent, key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonElement get(JsonObject o, String key) {
		if (o == null || !o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e;
	}

	JsonElement raw(String key) {
		JsonElement e = get(user, key);
		return e != null ? e : get(base, key);
	}

	double number(String key, double def) {
		JsonElement e = raw(key);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			double v = e.getAsDouble();
			return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
		} catch (RuntimeException ex) {
			return def;
		}
	}

	int integer(String key, int def) {
		return (int) Math.round(number(key, def));
	}

	boolean bool(String key, boolean def) {
		JsonElement e = raw(key);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			return e.getAsBoolean();
		} catch (RuntimeException ex) {
			return def;
		}
	}

	String string(String key, String def) {
		JsonElement e = raw(key);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			return e.getAsString();
		} catch (RuntimeException ex) {
			return def;
		}
	}

	JsonArray array(String key) {
		JsonElement e = raw(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
