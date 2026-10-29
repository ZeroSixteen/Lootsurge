package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/** Builds display stacks of the bundled Uniques for the creative tab. Client-side only in practice. */
public final class UniqueCatalog {
	private UniqueCatalog() {
	}

	public static List<ItemStack> bundledShowcase(HolderLookup.Provider registries) {
		List<ItemStack> out = new ArrayList<>();
		try {
			HolderLookup.RegistryLookup<Enchantment> enchants = registries.lookupOrThrow(Registries.ENCHANTMENT);
			String index = Defaults.readBundledText("pools/_index.txt");
			if (index == null) {
				return out;
			}
			for (String line : index.split("\\R")) {
				String file = line.trim();
				if (file.isEmpty() || file.startsWith("#")) {
					continue;
				}
				JsonObject root = Settings.readResource("/lootsurge/defaults/pools/" + file, LootSurgeMod.LOGGER);
				if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
					continue;
				}
				JsonArray entries = root.getAsJsonArray("entries");
				int i = 0;
				for (JsonElement e : entries) {
					if (e.isJsonObject() && e.getAsJsonObject().has("unique")) {
						LootEntry entry = PoolLoader.parseEntry(file, i, e.getAsJsonObject(), enchants,
								LootSurgeMod.LOGGER);
						if (entry != null && entry.uniqueId != null) {
							out.add(entry.createShowcase());
						}
					}
					i++;
				}
			}
		} catch (RuntimeException e) {
			LootSurgeMod.LOGGER.error("[LootSurge] Could not build the creative tab items.", e);
		}
		return out;
	}
}
