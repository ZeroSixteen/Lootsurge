package com.zerosixteen.lootsurge.client;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.zerosixteen.lootsurge.ConfigFile;
import com.zerosixteen.lootsurge.Settings;

/**
 * Simple options screen opened from Mod Menu. Every click saves config.json at once and asks the running
 * (single-player) server to reload it. Tier odds, pools and sounds are edited in the JSON files.
 */
public final class LootSurgeOptionsScreen extends Screen {
	private static final double[] CHANCE = { 0.5, 1.0, 2.0, 3.0, 5.0, 10.0 };
	private static final int[] PILLARS = { 0, 2, 4, 8, 16, 32 };

	private final Screen parent;

	public LootSurgeOptionsScreen(Screen parent) {
		super(Component.literal("Loot Surge Options"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Settings s = ConfigFile.read();
		int w = 230;
		int x = this.width / 2 - w / 2;
		int y = Math.max(20, this.height / 2 - 100);
		int step = 24;

		Button header = this.addRenderableWidget(
				Button.builder(Component.literal("Loot Surge by ZeroSixteen"), button -> { }).pos(x, y).size(w, 20).build());
		header.active = false;
		y += step + 6;

		addToggle(x, y, w, "Bonus loot", "enabled", s.enabled);
		y += step;
		addToggle(x, y, w, "Only when a player kills", "requirePlayerKill", s.requirePlayerKill);
		y += step;
		addToggle(x, y, w, "Skip baby mobs", "skipBabyMobs", s.skipBabyMobs);
		y += step;
		addToggle(x, y, w, "Debug logging", "debugLogging", s.debugLogging);
		y += step;

		int chanceIndex = 0;
		for (int i = 0; i < CHANCE.length; i++) {
			if (Math.abs(CHANCE[i] - s.globalChanceMultiplier) < Math.abs(CHANCE[chanceIndex] - s.globalChanceMultiplier)) {
				chanceIndex = i;
			}
		}
		addCycle(x, y, w, "Drop chance multiplier: x", CHANCE.length, chanceIndex, i -> CHANCE[i] + "",
				i -> ConfigFile.set("globalChanceMultiplier", CHANCE[i]));
		y += step;

		int pillarIndex = 0;
		for (int i = 0; i < PILLARS.length; i++) {
			if (Math.abs(PILLARS[i] - s.maxActivePillars) < Math.abs(PILLARS[pillarIndex] - s.maxActivePillars)) {
				pillarIndex = i;
			}
		}
		addCycle(x, y, w, "Max light pillars: ", PILLARS.length, pillarIndex, i -> PILLARS[i] + "",
				i -> ConfigFile.set("maxActivePillars", PILLARS[i]));
		y += step + 6;

		Button more = this.addRenderableWidget(Button.builder(Component.literal("Tier odds, sounds and pools: edit config/lootsurge"),
				button -> { }).pos(x, y).size(w, 20).build());
		more.active = false;

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
				.pos(x, this.height - 28).size(w, 20).build());
	}

	private void addToggle(int x, int y, int w, String label, String key, boolean initial) {
		boolean[] state = { initial };
		this.addRenderableWidget(Button.builder(toggleText(label, state[0]), button -> {
			state[0] = !state[0];
			ConfigFile.set(key, state[0]);
			button.setMessage(toggleText(label, state[0]));
		}).pos(x, y).size(w, 20).build());
	}

	private static Component toggleText(String label, boolean on) {
		return Component.literal(label + ": " + (on ? "ON" : "OFF"));
	}

	private interface Namer {
		String name(int index);
	}

	private void addCycle(int x, int y, int w, String label, int count, int start, Namer namer, IntConsumer onChange) {
		int[] index = { start };
		this.addRenderableWidget(Button.builder(Component.literal(label + namer.name(index[0])), button -> {
			index[0] = (index[0] + 1) % count;
			onChange.accept(index[0]);
			button.setMessage(Component.literal(label + namer.name(index[0])));
		}).pos(x, y).size(w, 20).build());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
