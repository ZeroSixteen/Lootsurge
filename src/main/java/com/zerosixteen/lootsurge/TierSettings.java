package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;

/** Everything configurable about one tier: odds, item counts and the full feedback package. */
final class TierSettings {
	enum Announce {
		NONE, ACTIONBAR, CHAT, BROADCAST;

		static Announce of(String s) {
			if (s != null) {
				for (Announce a : values()) {
					if (a.name().equals(s.trim().toUpperCase(Locale.ROOT))) {
						return a;
					}
				}
			}
			return NONE;
		}
	}

	record SoundCue(SoundEvent event, float volume, float pitch, int delayTicks) {
	}

	record Burst(ParticleOptions particle, int count, double spread, double speed) {
	}

	record Ring(ParticleOptions particle, int count, double radius) {
	}

	record Pillar(boolean enabled, ParticleOptions core, ParticleOptions accent, double height, double segmentLength,
			int coreCount, int accentCount, double spread, int intervalTicks, int maxTicks) {
	}

	final Tier tier;
	final String displayName;
	final ChatFormatting color;
	final double weight;
	final int minItems;
	final int maxItems;
	final boolean itemLabel;
	final Announce announce;
	final List<SoundCue> sounds;
	final Burst burst;
	final Ring ring;
	final Pillar pillar;

	private TierSettings(Tier tier, String displayName, ChatFormatting color, double weight, int minItems, int maxItems,
			boolean itemLabel, Announce announce, List<SoundCue> sounds, Burst burst, Ring ring, Pillar pillar) {
		this.tier = tier;
		this.displayName = displayName;
		this.color = color;
		this.weight = weight;
		this.minItems = minItems;
		this.maxItems = maxItems;
		this.itemLabel = itemLabel;
		this.announce = announce;
		this.sounds = sounds;
		this.burst = burst;
		this.ring = ring;
		this.pillar = pillar;
	}

	static TierSettings parse(Tier tier, Layered c, Logger log) {
		String where = "tiers." + tier.key();
		String name = c.string("displayName", capitalize(tier.key()));
		ChatFormatting color = Ids.color(c.string("color", "white"));
		double weight = Layered.clamp(c.number("weight", 1.0), 0.0, 1_000_000.0);
		int min = Layered.clamp(c.integer("minItems", 1), 1, 8);
		int max = Layered.clamp(c.integer("maxItems", min), min, 8);
		boolean label = c.bool("itemLabel", true);
		Announce announce = Announce.of(c.string("announce", "none"));

		List<SoundCue> sounds = new ArrayList<>();
		SoundCue main = cue(c.child("sound"), log, where + ".sound");
		if (main != null) {
			sounds.add(main);
		}
		JsonArray extra = c.array("extraSounds");
		if (extra != null) {
			int i = 0;
			for (JsonElement e : extra) {
				if (e.isJsonObject() && sounds.size() < 8) {
					JsonObject o = e.getAsJsonObject();
					SoundCue extraCue = cue(new Layered(o, null), log, where + ".extraSounds[" + i + "]");
					if (extraCue != null) {
						sounds.add(extraCue);
					}
				}
				i++;
			}
		}

		Layered b = c.child("burst");
		ParticleOptions burstParticle = particle(b.string("particle", "none"), log, where + ".burst.particle");
		Burst burst = new Burst(burstParticle, Layered.clamp(b.integer("count", 0), 0, 300),
				Layered.clamp(b.number("spread", 0.3), 0.0, 4.0), Layered.clamp(b.number("speed", 0.1), 0.0, 2.0));

		Layered r = c.child("ring");
		ParticleOptions ringParticle = particle(r.string("particle", "none"), log, where + ".ring.particle");
		Ring ring = new Ring(ringParticle, Layered.clamp(r.integer("count", 0), 0, 64),
				Layered.clamp(r.number("radius", 1.2), 0.2, 5.0));

		Layered p = c.child("pillar");
		boolean pillarOn = p.bool("enabled", false);
		ParticleOptions core = particle(p.string("particle", "end_rod"), log, where + ".pillar.particle");
		ParticleOptions accent = particle(p.string("accentParticle", "none"), log, where + ".pillar.accentParticle");
		double segLen = Layered.clamp(p.number("segmentLength", 2.5), 2.0, 8.0);
		Pillar pillar = new Pillar(pillarOn && core != null, core, accent,
				Layered.clamp(p.number("height", 16.0), 2.0, 64.0), segLen,
				Layered.clamp(p.integer("particlesPerSegment", 4), 1, 16),
				Layered.clamp(p.integer("accentParticlesPerSegment", 2), 0, 16),
				Layered.clamp(p.number("spread", 0.08), 0.0, 1.0),
				Layered.clamp(p.integer("intervalTicks", 5), 3, 40),
				Layered.clamp(p.integer("maxSeconds", 20), 1, 600) * 20);

		return new TierSettings(tier, name, color, weight, min, max, label, announce, List.copyOf(sounds), burst, ring,
				pillar);
	}

	private static SoundCue cue(Layered c, Logger log, String where) {
		String id = c.string("id", "none");
		if (id.isBlank() || id.equalsIgnoreCase("none")) {
			return null;
		}
		SoundEvent event = Ids.sound(id);
		if (event == null) {
			log.warn("[LootSurge] {}: unknown sound '{}', that sound is skipped.", where, id);
			return null;
		}
		return new SoundCue(event, (float) Layered.clamp(c.number("volume", 1.0), 0.0, 4.0),
				(float) Layered.clamp(c.number("pitch", 1.0), 0.5, 2.0),
				Layered.clamp(c.integer("delayTicks", 0), 0, 200));
	}

	private static ParticleOptions particle(String id, Logger log, String where) {
		if (id == null || id.isBlank() || id.equalsIgnoreCase("none")) {
			return null;
		}
		ParticleOptions p = Ids.particle(id);
		if (p == null) {
			log.warn("[LootSurge] {}: unknown or unsupported particle '{}', it is skipped.", where, id);
		}
		return p;
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
