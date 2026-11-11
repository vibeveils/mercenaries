package dev.mercenaries.weapon;

import java.util.EnumSet;
import java.util.Set;

import dev.mercenaries.MercClass;

/**
 * Immutable description of a weapon. Damage numbers are TF2 units (converted with MercConfig.dmg),
 * times are in ticks (20/s), distances in blocks, speeds in blocks/tick.
 */
public record WeaponDef(
		String id,
		String name,
		String description,
		MercClass mercClass,
		Slot slot,
		Kind kind,
		double damage,
		int pellets,
		float spread,
		int fireInterval,
		int clip,
		int reserve,
		int reloadTicks,
		boolean reloadSingly,
		float projectileSpeed,
		float radius,
		float range,
		Set<Trait> traits
) {
	public enum Slot { PRIMARY, SECONDARY, MELEE, PDA }

	public enum Kind {
		HITSCAN, MINIGUN, FLAMETHROWER, SCOPED, BOW,
		ROCKET, GRENADE, STICKY, FLARE, SYRINGE, JAR,
		MEDIGUN, SWING, CONSUMABLE, BUILDER, SAPPER
	}

	public enum Trait {
		/** Ramp-up/fall-off on hitscan damage by distance. */
		FALLOFF,
		/** Can headshot for a crit (sniper rifle, Ambassador, Huntsman). */
		HEADSHOT,
		/** Instant kill from behind (knife). */
		BACKSTAB,
		/** Crits targets that are on fire (Axtinguisher, Flare Gun). */
		CRIT_BURNING,
		/** Mini-crits targets airborne from explosions (Direct Hit). */
		MINICRIT_AIRBORNE,
		/** Heals the wielder on hit (Black Box, Blutsauger). */
		HEAL_ON_HIT,
		/** Slows targets on hit (Natascha, Sandman). */
		SLOW_ON_HIT,
		/** Sets targets on fire (flamethrowers, flares, Backburner). */
		IGNITE,
		/** Crits from behind (Backburner). */
		CRIT_FROM_BEHIND,
		/** No airblast (Backburner). */
		NO_AIRBLAST,
		/** Melee damage grows as wielder's health drops (Equalizer). */
		LOW_HEALTH_BONUS,
		/** Reduces blast self-damage while equipped anywhere (Gunboats, passive). */
		BLAST_RESIST,
		/** Builds Über on hit (Ubersaw). */
		UBER_ON_HIT,
		/** Kritzkrieg: faster build, Über gives crits instead of invulnerability. */
		KRITZ,
		/** Melee speed boost while held (Gloves of Running Urgently). */
		SPEED_WHILE_HELD,
		/** Causes bleed (Tribalman's Shiv). */
		BLEED,
		/** Passive: no projectile (Gunboats is "worn"). */
		PASSIVE,
		/** Rocket: lands faster, smaller radius (Direct Hit). */
		FAST_PROJECTILE,
		/** Pipes shatter on surfaces instead of bouncing (Loch-n-Load). */
		NO_BOUNCE,
		/** Stickybomb launcher that can only detonate stickies near the crosshair (Scottish Resistance). */
		SELECTIVE_DETONATE,
		/** Minigun spins up faster (Tomislav). */
		FAST_SPIN,
		/** Consumable: heal (Sandvich). */
		HEAL_FOOD,
		/** Consumable: temporary invulnerability (Bonk!). */
		BONK,
		/** Speeds up fire rate / damage bonus: revenge crits (Frontier Justice) */
		REVENGE,
		/** Melee +health on equip (Gunslinger). */
		EXTRA_HEALTH,
		/** Health penalty while equipped (Big Earner, Eyelander). */
		LESS_HEALTH,
		/** Melee extra reach (Eyelander). */
		LONG_REACH
	}

	public boolean has(Trait t) {
		return traits.contains(t);
	}

	public boolean usesAmmo() {
		return clip > 0 || reserve > 0;
	}

	public boolean isRanged() {
		return kind != Kind.SWING;
	}

	// ------------------------------------------------------------------ builder

	public static Builder of(String id, String name, MercClass c, Slot slot, Kind kind) {
		return new Builder(id, name, c, slot, kind);
	}

	public static final class Builder {
		private final String id, name;
		private final MercClass c;
		private final Slot slot;
		private final Kind kind;
		private String desc = "";
		private double damage = 0;
		private int pellets = 1;
		private float spread = 0;
		private int interval = 10;
		private int clip = 0, reserve = 0, reload = 20;
		private boolean singly = false;
		private float speed = 1.2f, radius = 3.0f, range = 64f;
		private final EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);

		Builder(String id, String name, MercClass c, Slot slot, Kind kind) {
			this.id = id;
			this.name = name;
			this.c = c;
			this.slot = slot;
			this.kind = kind;
		}

		public Builder desc(String d) { this.desc = d; return this; }
		public Builder damage(double d) { this.damage = d; return this; }
		public Builder pellets(int p, float spreadDegrees) { this.pellets = p; this.spread = spreadDegrees; return this; }
		public Builder spread(float s) { this.spread = s; return this; }
		public Builder interval(int ticks) { this.interval = ticks; return this; }
		public Builder ammo(int clip, int reserve) { this.clip = clip; this.reserve = reserve; return this; }
		public Builder reload(int ticks, boolean oneAtATime) { this.reload = ticks; this.singly = oneAtATime; return this; }
		public Builder projectile(float speed, float radius) { this.speed = speed; this.radius = radius; return this; }
		public Builder range(float r) { this.range = r; return this; }
		public Builder traits(Trait... t) { for (Trait x : t) traits.add(x); return this; }

		public WeaponDef build() {
			return new WeaponDef(id, name, desc, c, slot, kind, damage, pellets, spread, interval, clip, reserve,
					reload, singly, speed, radius, range, Set.copyOf(traits.isEmpty() ? EnumSet.noneOf(Trait.class) : traits));
		}
	}
}
