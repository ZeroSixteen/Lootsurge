package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Builds the hover text of a Unique from its real powers, so the tooltip always matches what the item does:
 * a flavor line, how to trigger it (right-click, activity, passive) and what happens. Text only, no gameplay change.
 */
final class Tooltips {
	static volatile boolean enabled = true;
	private static final Map<String, String> FLAVOR = new ConcurrentHashMap<>();

	private static final Map<String, String> WHEN = Map.ofEntries(Map.entry("woodcutting", "chop a tree"),
			Map.entry("mining", "mine stone or ore"), Map.entry("excavation", "dig with a shovel"),
			Map.entry("nature", "harvest a plant, flower or leaves"), Map.entry("farming", "harvest a fully grown crop"),
			Map.entry("shear", "shear an animal"), Map.entry("milk", "milk an animal"), Map.entry("honey", "collect honey"),
			Map.entry("breed", "breed animals"), Map.entry("egg", "collect a laid egg"), Map.entry("tame", "tame an animal"),
			Map.entry("horse", "tame a horse"), Map.entry("fishing", "catch a fish"),
			Map.entry("archaeology", "brush suspicious sand or gravel"), Map.entry("brewing", "brew a potion"),
			Map.entry("water", "scoop water with a bucket"), Map.entry("lava", "scoop lava with a bucket"),
			Map.entry("hurt", "take damage"), Map.entry("hit", "hit a mob"),
			Map.entry("sprint_burst", "release a full sprint charge"), Map.entry("last_stand", "would die"));

	private Tooltips() {
	}

	static void loadFlavor(org.slf4j.Logger log) {
		JsonObject o = Settings.readResource("/lootsurge/defaults/flavor.json", log);
		FLAVOR.clear();
		if (o != null) {
			o.entrySet().forEach(e -> {
				if (e.getValue().isJsonPrimitive()) {
					FLAVOR.put(e.getKey(), e.getValue().getAsString());
				}
			});
		}
	}

	static void apply(ItemStack stack, String id, UniquePowers.Powers p) {
		if (FLAVOR.isEmpty()) {
			loadFlavor(LootSurgeMod.LOGGER);
		}
		if (!enabled || stack == null || stack.isEmpty() || id == null || p == null) {
			return;
		}
		ItemLore existing = stack.get(DataComponents.LORE);
		if (existing != null && !existing.lines().isEmpty()) {
			return;
		}
		stack.set(DataComponents.LORE, new ItemLore(build(id, p)));
	}

	private static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(color).withStyle(s -> s.withItalic(false));
	}

	static List<Component> build(String id, UniquePowers.Powers p) {
		List<Component> out = new ArrayList<>();
		String flavor = FLAVOR.get(id);
		if (flavor != null && !flavor.isBlank()) {
			out.add(line("\"" + flavor + "\"", ChatFormatting.GRAY));
			out.add(line(" ", ChatFormatting.GRAY));
		}
		if (p.shoot() != null) {
			out.add(line("Right-click: shoot a " + p.shoot() + " fireball (" + secs(p.cooldownTicks()) + " cooldown)", ChatFormatting.GOLD));
		}
		if (!p.useActions().isEmpty()) {
			out.add(line("Right-click (" + secs(p.cooldownTicks()) + " cooldown):", ChatFormatting.GOLD));
			for (UniquePowers.Action a : p.useActions()) {
				String d = describe(a);
				if (d != null) {
					out.add(line("  - " + d, ChatFormatting.YELLOW));
				}
			}
		}
		for (UniquePowers.Trigger t : p.triggers()) {
			List<String> parts = new ArrayList<>();
			for (UniquePowers.Action a : t.actions()) {
				String d = describe(a);
				if (d != null) {
					parts.add(d);
				}
			}
			if (parts.isEmpty()) {
				continue;
			}
			List<String> when = new ArrayList<>();
			for (String on : new java.util.TreeSet<>(t.on())) {
				when.add(WHEN.getOrDefault(on, on));
			}
			String chance = t.chance() >= 1.0 ? "" : " " + pct(t.chance()) + " chance,";
			out.add(line("When you " + String.join(" / ", when) + ":" + (chance.isEmpty() ? "" : chance.substring(0, chance.length() - 1))
					+ (t.cooldown() > 0 ? " (" + secs(t.cooldown()) + " cooldown)" : ""), ChatFormatting.GOLD));
			for (String d : parts) {
				out.add(line("  - " + d, ChatFormatting.YELLOW));
			}
		}
		if (p.lifesteal() > 0) {
			out.add(line("Hits heal you for " + pct(p.lifesteal()) + " of damage dealt", ChatFormatting.GOLD));
		}
		if (p.igniteSeconds() > 0) {
			out.add(line("Hits set targets on fire for " + (int) p.igniteSeconds() + "s", ChatFormatting.GOLD));
		}
		if (p.lightningChance() > 0) {
			out.add(line("Hits have a " + pct(p.lightningChance()) + " chance to call lightning", ChatFormatting.GOLD));
		}
		if (p.explosionChance() > 0 && p.explosionPower() > 0) {
			out.add(line("Hits have a " + pct(p.explosionChance()) + " chance to explode", ChatFormatting.GOLD));
		}
		for (UniquePowers.EffectSpec e : p.targetEffects()) {
			out.add(line("Hits inflict " + effect(e.effect(), e.amplifier()) + " (" + e.seconds() + "s)", ChatFormatting.GOLD));
		}
		List<String> passive = new ArrayList<>();
		for (UniquePowers.EffectSpec e : p.effects()) {
			passive.add(effect(e.effect(), e.amplifier()));
		}
		if (!passive.isEmpty()) {
			out.add(line("While worn or held: " + String.join(", ", passive), ChatFormatting.AQUA));
		}
		if (!p.immune().isEmpty()) {
			out.add(line("Immune to: " + String.join(", ", new java.util.TreeSet<>(p.immune())), ChatFormatting.AQUA));
		}
		if (!p.cure().isEmpty()) {
			List<String> c = new ArrayList<>();
			for (Holder<MobEffect> h : p.cure()) {
				c.add(effect(h, -1));
			}
			out.add(line("Cures: " + String.join(", ", c), ChatFormatting.AQUA));
		}
		Set<String> f = p.flags();
		if (f.contains("lava_walk")) {
			out.add(line("Walk on lava (sneak to sink)", ChatFormatting.AQUA));
		}
		if (f.contains("magnet")) {
			out.add(line("Pulls nearby items and XP to you (sneak to stop)", ChatFormatting.AQUA));
		}
		if (f.contains("glow_hostiles")) {
			out.add(line("Nearby hostile mobs glow", ChatFormatting.AQUA));
		}
		if (f.contains("hunger_saver")) {
			out.add(line("Hunger drains much more slowly", ChatFormatting.AQUA));
		}
		if (f.contains("sprint_burst")) {
			out.add(line("Sprint for about " + (int) (p.param("chargeTicks", 100.0) / 20) + "s, then stop to release the charge", ChatFormatting.GOLD));
		}
		if (f.contains("last_stand")) {
			out.add(line("Survives one lethal hit, then recharges for " + (int) (p.param("lastStandCooldownTicks", 6000.0) / 20) + "s", ChatFormatting.GOLD));
		}
		out.add(line(" ", ChatFormatting.GRAY));
		out.add(line("Unique", ChatFormatting.GOLD));
		return out;
	}

	private static String describe(UniquePowers.Action a) {
		String chance = a.chance() < 1.0 ? pct(a.chance()) + " chance: " : "";
		String s = switch (a.type()) {
			case "drop" -> {
				List<String> names = new ArrayList<>();
				for (UniquePowers.Drop d : a.drops()) {
					names.add(pretty(d.item()));
					if (names.size() >= 5) {
						names.add("...");
						break;
					}
				}
				yield "bonus loot: " + String.join(", ", names);
			}
			case "duplicate" -> "doubles the block's drops";
			case "xp" -> "+" + (int) a.amount() + " XP";
			case "effect" -> effect(a.effect(), a.amplifier()) + " for " + a.seconds() + "s";
			case "effect_nearby" -> "nearby hostiles get " + effect(a.effect(), a.amplifier()) + " (" + a.seconds() + "s)";
			case "lightning" -> "lightning strikes (harmless to you)";
			case "shockwave" -> "shockwave knocks nearby mobs back";
			case "grow" -> "ripens nearby crops";
			case "spawn" -> "summons a temporary " + a.id().replace("minecraft:", "").replace('_', ' ') + " (" + a.seconds() + "s)";
			case "reveal" -> "marks nearby ore with particles";
			case "pull" -> "pulls nearby items and XP to you";
			case "heal" -> "heals " + (int) (a.amount() / 2) + " hearts";
			case "teleport" -> "teleports you a short way";
			case "repair" -> "repairs your held item";
			case "summon_hiro" -> "calls Hiro's spirit for " + a.seconds() + "s";
			default -> null;
		};
		return s == null ? null : chance + s;
	}

	private static String effect(Holder<MobEffect> h, int amp) {
		String n = pretty(BuiltInRegistries.MOB_EFFECT.getKey(h.value()).getPath());
		return amp > 0 ? n + " " + roman(amp + 1) : n;
	}

	private static String pretty(Item item) {
		return pretty(BuiltInRegistries.ITEM.getKey(item).getPath());
	}

	private static String pretty(String path) {
		StringBuilder sb = new StringBuilder();
		for (String w : path.split("_")) {
			if (!w.isEmpty()) {
				sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return sb.toString();
	}

	private static String roman(int n) {
		String[] r = { "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
		return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
	}

	private static String pct(double v) {
		double x = v * 100.0;
		return (x == Math.rint(x) ? String.valueOf((int) x) : String.format(Locale.ROOT, "%.1f", x)) + "%";
	}

	private static String secs(int ticks) {
		return (ticks % 20 == 0 ? String.valueOf(ticks / 20) : String.format(Locale.ROOT, "%.1f", ticks / 20.0)) + "s";
	}
}
