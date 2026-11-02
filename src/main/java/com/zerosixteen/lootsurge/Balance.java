package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;

/**
 * Bundled balance overlay (lootsurge/defaults/balance.json). It only ever raises a Unique's stats: numeric params take
 * the larger of the config value and the bundled value ("paramsMin" takes the smaller, for cooldowns), effects are
 * added or raised, lifesteal is raised. Because it runs on top of whatever is in config/lootsurge/uniques, existing
 * installs get the buffs without touching their files. Set "applyBundledBalance": false in config.json to turn it off.
 */
final class Balance {
	static volatile boolean enabled = true;
	private static volatile Map<String, JsonObject> table;

	private Balance() {
	}

	private static Map<String, JsonObject> table(Logger log) {
		Map<String, JsonObject> t = table;
		if (t == null) {
			t = new HashMap<>();
			JsonObject root = Settings.readResource("/lootsurge/defaults/balance.json", log);
			if (root != null) {
				for (Map.Entry<String, JsonElement> e : root.entrySet()) {
					if (e.getValue().isJsonObject() && !e.getKey().startsWith("_")) {
						t.put(e.getKey(), e.getValue().getAsJsonObject());
					}
				}
			}
			table = t;
		}
		return t;
	}

	static UniquePowers.Powers apply(String id, UniquePowers.Powers p, Logger log) {
		if (!enabled || p == null || id == null) {
			return p;
		}
		JsonObject o = table(log).get(id);
		if (o == null) {
			return p;
		}
		try {
			Map<String, Double> params = new HashMap<>(p.params());
			merge(params, o.getAsJsonObject("params"), true);
			merge(params, o.getAsJsonObject("paramsMin"), false);

			List<UniquePowers.EffectSpec> effects = new ArrayList<>(p.effects());
			if (o.has("effects") && o.get("effects").isJsonArray()) {
				for (JsonElement el : o.getAsJsonArray("effects")) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject eo = el.getAsJsonObject();
					Identifier eid = Ids.parse(eo.get("id").getAsString());
					MobEffect me = eid == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(eid).orElse(null);
					if (me == null) {
						continue;
					}
					Holder<MobEffect> h = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(me);
					int amp = eo.has("amplifier") ? eo.get("amplifier").getAsInt() : 0;
					boolean found = false;
					for (int i = 0; i < effects.size(); i++) {
						UniquePowers.EffectSpec cur = effects.get(i);
						if (cur.effect().equals(h)) {
							found = true;
							if (amp > cur.amplifier()) {
								effects.set(i, new UniquePowers.EffectSpec(h, amp, cur.seconds()));
							}
						}
					}
					if (!found) {
						effects.add(new UniquePowers.EffectSpec(h, amp, 0));
					}
				}
			}
			double lifesteal = p.lifesteal();
			if (o.has("lifesteal")) {
				lifesteal = Math.max(lifesteal, Math.min(2.0, o.get("lifesteal").getAsDouble()));
			}
			return new UniquePowers.Powers(List.copyOf(effects), p.cure(), p.immune(), p.lightningChance(), p.igniteSeconds(),
					p.explosionChance(), p.explosionPower(), lifesteal, p.targetEffects(), p.shoot(), p.shootPower(),
					p.cooldownTicks(), p.triggers(), p.flags(), Map.copyOf(params), p.useActions());
		} catch (RuntimeException e) {
			log.warn("[LootSurge] Could not apply bundled balance for {} ({}). Using the config values.", id, e.getMessage());
			return p;
		}
	}

	private static void merge(Map<String, Double> params, JsonObject src, boolean larger) {
		if (src == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : src.entrySet()) {
			if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) {
				continue;
			}
			double v = e.getValue().getAsDouble();
			Double cur = params.get(e.getKey());
			params.put(e.getKey(), cur == null ? v : (larger ? Math.max(cur, v) : Math.min(cur, v)));
		}
	}
}
