package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * All the visual and audio "jackpot" feedback. Everything here runs on the server thread and only sends standard
 * particle and sound packets, so vanilla clients (no mod installed) see and hear all of it.
 *
 * <p>The light pillar is a column of stacked particle clouds that is refreshed every few ticks for as long as the
 * dropped item exists (up to a configured maximum), so it disappears when someone picks the item up.
 */
final class Effects {
	private static final class Delayed {
		final long dueTick;
		final ServerLevel level;
		final double x;
		final double y;
		final double z;
		final TierSettings.SoundCue cue;

		Delayed(long dueTick, ServerLevel level, double x, double y, double z, TierSettings.SoundCue cue) {
			this.dueTick = dueTick;
			this.level = level;
			this.x = x;
			this.y = y;
			this.z = z;
			this.cue = cue;
		}
	}

	private static final class ActivePillar {
		final ServerLevel level;
		final ItemEntity anchor;
		final TierSettings.Pillar pillar;
		final double fixedX;
		final double fixedY;
		final double fixedZ;
		final long expireTick;
		long nextPulse;

		ActivePillar(ServerLevel level, ItemEntity anchor, TierSettings.Pillar pillar, double x, double y, double z,
				long expireTick, long nextPulse) {
			this.level = level;
			this.anchor = anchor;
			this.pillar = pillar;
			this.fixedX = x;
			this.fixedY = y;
			this.fixedZ = z;
			this.expireTick = expireTick;
			this.nextPulse = nextPulse;
		}
	}

	private long tick;
	private final List<Delayed> delayed = new ArrayList<>();
	private final List<ActivePillar> pillars = new ArrayList<>();

	void clear() {
		delayed.clear();
		pillars.clear();
	}

	/** Called once per server tick. */
	void tick() {
		tick++;

		if (!delayed.isEmpty()) {
			Iterator<Delayed> it = delayed.iterator();
			List<Delayed> due = new ArrayList<>();
			while (it.hasNext()) {
				Delayed d = it.next();
				if (d.dueTick <= tick) {
					due.add(d);
					it.remove();
				}
			}
			for (Delayed d : due) {
				playCue(d.level, d.x, d.y, d.z, d.cue);
			}
		}

		if (!pillars.isEmpty()) {
			Iterator<ActivePillar> it = pillars.iterator();
			while (it.hasNext()) {
				ActivePillar p = it.next();
				boolean gone = p.anchor != null && p.anchor.isRemoved();
				if (gone || tick >= p.expireTick) {
					it.remove();
					continue;
				}
				if (tick >= p.nextPulse) {
					p.nextPulse = tick + p.pillar.intervalTicks();
					double px = p.anchor != null ? p.anchor.getX() : p.fixedX;
					double py = p.anchor != null ? p.anchor.getY() : p.fixedY;
					double pz = p.anchor != null ? p.anchor.getZ() : p.fixedZ;
					pulse(p.level, p.pillar, px, py, pz);
				}
			}
		}
	}

	/** Plays the whole feedback package for a drop at (x, y, z). feetY is the ground level for the ring. */
	void play(ServerLevel level, double x, double y, double z, double feetY, TierSettings t, List<ItemEntity> items,
			int maxActivePillars) {
		for (TierSettings.SoundCue cue : t.sounds) {
			if (cue.delayTicks() <= 0) {
				playCue(level, x, y, z, cue);
			} else {
				delayed.add(new Delayed(tick + cue.delayTicks(), level, x, y, z, cue));
			}
		}

		TierSettings.Burst burst = t.burst;
		if (burst.particle() != null && burst.count() > 0) {
			level.sendParticles(burst.particle(), x, y, z, burst.count(), burst.spread(), burst.spread(),
					burst.spread(), burst.speed());
		}

		TierSettings.Ring ring = t.ring;
		if (ring.particle() != null && ring.count() > 0) {
			for (int i = 0; i < ring.count(); i++) {
				double angle = (Math.PI * 2.0) * i / ring.count();
				level.sendParticles(ring.particle(), x + Math.cos(angle) * ring.radius(), feetY + 0.1,
						z + Math.sin(angle) * ring.radius(), 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		addPillar(level, items.isEmpty() ? null : items.get(0), t.pillar, x, feetY, z, maxActivePillars);
	}

	/**
	 * Starts a light pillar over a dropped item. Safe to call again for the same item (it is ignored), which is how
	 * a re-dropped or reloaded item gets its pillar back.
	 */
	void addPillar(ServerLevel level, ItemEntity anchor, TierSettings.Pillar pillar, double x, double y, double z,
			int maxActivePillars) {
		if (!pillar.enabled() || maxActivePillars <= 0) {
			return;
		}
		if (anchor != null) {
			for (ActivePillar existing : pillars) {
				if (existing.anchor == anchor) {
					return;
				}
			}
		}
		while (pillars.size() >= maxActivePillars) {
			pillars.remove(0);
		}
		pillars.add(new ActivePillar(level, anchor, pillar, x, y, z, tick + pillar.maxTicks(), tick + 1));
	}

	private static void playCue(ServerLevel level, double x, double y, double z, TierSettings.SoundCue cue) {
		level.playSound((Player) null, x, y, z, cue.event(), SoundSource.PLAYERS, cue.volume(), cue.pitch());
	}

	/** One refresh of the light pillar: a stack of particle clouds from the item upwards. */
	private static void pulse(ServerLevel level, TierSettings.Pillar p, double x, double y, double z) {
		int segments = (int) Math.ceil(p.height() / p.segmentLength());
		ParticleOptions core = p.core();
		ParticleOptions accent = p.accent();
		for (int i = 0; i < segments; i++) {
			double cy = y + 0.2 + p.segmentLength() * (i + 0.5);
			level.sendParticles(core, x, cy, z, p.coreCount(), p.spread(), p.segmentLength() * 0.25, p.spread(), 0.0);
			if (accent != null && p.accentCount() > 0) {
				level.sendParticles(accent, x, cy, z, p.accentCount(), p.spread() * 3.0, p.segmentLength() * 0.3,
						p.spread() * 3.0, 0.0);
			}
		}
	}
}
