package dev.mercenaries;

import java.util.Locale;

/**
 * The nine playable mercenary classes plus NONE (plain vanilla Minecraft player).
 *
 * <p>Health and speed use TF2's numbers; health is converted to Minecraft half-hearts with
 * {@link MercConfig#hpScale()} and speed is relative to TF2's 300 HU/s baseline.
 */
public enum MercClass {
	NONE(0, 300, 0.0, 0xFFFFFF, "Plain Minecraft. No class abilities, no loadout."),
	SCOUT(125, 400, 0.0, 0x7B9BB8, "Fast. Double jump. Strong up close."),
	SOLDIER(200, 240, 0.0, 0x8C6E4A, "Rocket launcher, rocket jumping, tough."),
	PYRO(175, 300, 0.0, 0xC2553B, "Flamethrower and airblast. Shrugs off fire."),
	DEMOMAN(175, 280, 0.0, 0x4F6E3A, "Grenades and remote stickybombs."),
	HEAVY(300, 230, 0.5, 0x9A4B3D, "Huge health pool and a minigun. Hard to knock back."),
	ENGINEER(125, 300, 0.0, 0xC79A3E, "Builds sentry guns and dispensers using metal."),
	MEDIC(150, 320, 0.0, 0x6E8CC0, "Heals teammates, builds and deploys ÜberCharge. Regenerates."),
	SNIPER(125, 300, 0.0, 0x6B7A3B, "Charged long-range shots with headshots."),
	SPY(125, 320, 0.0, 0x4A4A5C, "Backstabs, cloaks and saps enemy buildings.");

	public final int tf2Health;
	public final int tf2Speed;
	public final double knockbackResistance;
	public final int color;
	public final String blurb;

	MercClass(int tf2Health, int tf2Speed, double knockbackResistance, int color, String blurb) {
		this.tf2Health = tf2Health;
		this.tf2Speed = tf2Speed;
		this.knockbackResistance = knockbackResistance;
		this.color = color;
		this.blurb = blurb;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String displayName() {
		return this == DEMOMAN ? "Demoman" : name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
	}

	/** Max health in Minecraft health points (2 = one heart). */
	public float maxHealth() {
		return this == NONE ? 20f : (float) Math.round(tf2Health / MercConfig.get().hpScale());
	}

	/** Multiplier applied on top of the vanilla walk speed. */
	public double speedMultiplier() {
		return this == NONE ? 1.0 : tf2Speed / 300.0;
	}

	public static MercClass byOrdinal(int i) {
		MercClass[] v = values();
		return i >= 0 && i < v.length ? v[i] : NONE;
	}
}
