package dev.mercenaries.weapon;

import static dev.mercenaries.MercClass.*;
import static dev.mercenaries.weapon.WeaponDef.Kind.*;
import static dev.mercenaries.weapon.WeaponDef.Slot.*;
import static dev.mercenaries.weapon.WeaponDef.Trait.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import dev.mercenaries.MercClass;
import dev.mercenaries.MercConfig;
import dev.mercenaries.Mercenaries;

/**
 * Every weapon in the mod. Stats are approximations of TF2's public numbers (damage in TF2 HP,
 * time in ticks). Descriptions are original summaries.
 */
public final class Weapons {
	private static final Map<String, WeaponDef> DEFS = new LinkedHashMap<>();
	private static final Map<String, MercWeaponItem> ITEMS = new LinkedHashMap<>();

	private Weapons() {
	}

	private static void add(WeaponDef.Builder b) {
		WeaponDef d = b.build();
		DEFS.put(d.id(), d);
	}

	static {
		// ---------------------------------------------------------------- Scout
		add(WeaponDef.of("scattergun", "Scattergun", SCOUT, PRIMARY, HITSCAN).damage(6).pellets(10, 5.5f).interval(13)
				.ammo(6, 32).reload(10, true).traits(FALLOFF).desc("10-pellet shotgun. Devastating at close range."));
		add(WeaponDef.of("force_a_nature", "Force-A-Nature", SCOUT, PRIMARY, HITSCAN).damage(5.4).pellets(12, 6f).interval(6)
				.ammo(2, 32).reload(28, false).traits(FALLOFF).desc("Double-barrel. Fires faster, knocks targets (and you) back."));
		add(WeaponDef.of("pistol", "Pistol", SCOUT, SECONDARY, HITSCAN).damage(15).spread(1.5f).interval(3)
				.ammo(12, 36).reload(25, false).traits(FALLOFF).desc("Rapid semi-automatic sidearm."));
		add(WeaponDef.of("bonk", "Bonk! Atomic Punch", SCOUT, SECONDARY, CONSUMABLE).interval(440)
				.traits(BONK).desc("Drink to become invulnerable for 8 seconds, but you can't attack. Recharges."));
		add(WeaponDef.of("bat", "Bat", SCOUT, MELEE, SWING).damage(35).interval(10).desc("Fast melee swing."));
		add(WeaponDef.of("sandman", "Sandman", SCOUT, MELEE, SWING).damage(35).interval(10).traits(SLOW_ON_HIT, LESS_HEALTH)
				.desc("Hits slow targets. -15 max health."));

		// ---------------------------------------------------------------- Soldier
		add(WeaponDef.of("rocket_launcher", "Rocket Launcher", SOLDIER, PRIMARY, ROCKET).damage(90).interval(16)
				.ammo(4, 20).reload(16, true).projectile(1.2f, 3.2f).desc("Explosive rockets. Shoot the ground to rocket jump."));
		add(WeaponDef.of("direct_hit", "Direct Hit", SOLDIER, PRIMARY, ROCKET).damage(112).interval(16)
				.ammo(4, 20).reload(16, true).projectile(2.15f, 1.0f).traits(FAST_PROJECTILE, MINICRIT_AIRBORNE)
				.desc("Much faster rockets, tiny blast. Mini-crits airborne targets."));
		add(WeaponDef.of("black_box", "Black Box", SOLDIER, PRIMARY, ROCKET).damage(90).interval(16)
				.ammo(3, 20).reload(16, true).projectile(1.2f, 3.2f).traits(HEAL_ON_HIT)
				.desc("Heals you for damage dealt. Holds 3 rockets."));
		add(WeaponDef.of("shotgun", "Shotgun", SOLDIER, SECONDARY, HITSCAN).damage(6).pellets(10, 5f).interval(13)
				.ammo(6, 32).reload(10, true).traits(FALLOFF).desc("Reliable 10-pellet shotgun. Shared by several classes."));
		add(WeaponDef.of("gunboats", "Gunboats", SOLDIER, SECONDARY, SWING).traits(PASSIVE, BLAST_RESIST)
				.desc("Passive boots: 60% less self-damage from your own rockets."));
		add(WeaponDef.of("shovel", "Shovel", SOLDIER, MELEE, SWING).damage(65).interval(16).desc("Standard melee."));
		add(WeaponDef.of("equalizer", "Equalizer", SOLDIER, MELEE, SWING).damage(33).interval(16).traits(LOW_HEALTH_BONUS)
				.desc("Damage rises as your health falls."));

		// ---------------------------------------------------------------- Pyro
		add(WeaponDef.of("flamethrower", "Flamethrower", PYRO, PRIMARY, FLAMETHROWER).damage(13).interval(2)
				.ammo(0, 200).range(6.5f).traits(IGNITE).desc("Short-range fire. Right-click to airblast projectiles and enemies."));
		add(WeaponDef.of("backburner", "Backburner", PYRO, PRIMARY, FLAMETHROWER).damage(13).interval(2)
				.ammo(0, 200).range(6.5f).traits(IGNITE, CRIT_FROM_BEHIND, NO_AIRBLAST).desc("Crits from behind. No airblast."));
		add(WeaponDef.of("flare_gun", "Flare Gun", PYRO, SECONDARY, FLARE).damage(30).interval(40)
				.ammo(1, 16).reload(20, false).projectile(2.0f, 0).traits(IGNITE, CRIT_BURNING).desc("Ignites targets. Crits burning enemies."));
		add(WeaponDef.of("fire_axe", "Fire Axe", PYRO, MELEE, SWING).damage(65).interval(16).desc("Standard melee."));
		add(WeaponDef.of("axtinguisher", "Axtinguisher", PYRO, MELEE, SWING).damage(44).interval(16).traits(CRIT_BURNING)
				.desc("Crits burning targets, weaker otherwise."));

		// ---------------------------------------------------------------- Demoman
		add(WeaponDef.of("grenade_launcher", "Grenade Launcher", DEMOMAN, PRIMARY, GRENADE).damage(100).interval(12)
				.ammo(4, 16).reload(12, true).projectile(1.1f, 3.2f).desc("Bouncing pipes that explode on direct hits or after 2 s."));
		add(WeaponDef.of("loch_n_load", "Loch-n-Load", DEMOMAN, PRIMARY, GRENADE).damage(100).interval(12)
				.ammo(3, 16).reload(12, true).projectile(1.4f, 2.4f).traits(NO_BOUNCE).desc("Faster pipes that shatter on surfaces."));
		add(WeaponDef.of("stickybomb_launcher", "Stickybomb Launcher", DEMOMAN, SECONDARY, STICKY).damage(120).interval(12)
				.ammo(8, 24).reload(13, true).projectile(1.0f, 3.2f).desc("Sticky bombs. Right-click to detonate. Max 8."));
		add(WeaponDef.of("scottish_resistance", "Scottish Resistance", DEMOMAN, SECONDARY, STICKY).damage(120).interval(12)
				.ammo(8, 36).reload(13, true).projectile(1.0f, 3.2f).traits(SELECTIVE_DETONATE)
				.desc("Detonates only stickies near your crosshair. Bigger reserve."));
		add(WeaponDef.of("bottle", "Bottle", DEMOMAN, MELEE, SWING).damage(65).interval(16).desc("Standard melee."));
		add(WeaponDef.of("eyelander", "Eyelander", DEMOMAN, MELEE, SWING).damage(65).interval(16).traits(LONG_REACH, LESS_HEALTH)
				.desc("Longer reach. -25 max health."));

		// ---------------------------------------------------------------- Heavy
		add(WeaponDef.of("minigun", "Minigun", HEAVY, PRIMARY, MINIGUN).damage(9).pellets(4, 4f).interval(2)
				.ammo(0, 200).traits(FALLOFF).desc("Hold right-click to spin, left-click to fire. Slows you while spun."));
		add(WeaponDef.of("natascha", "Natascha", HEAVY, PRIMARY, MINIGUN).damage(6.75).pellets(4, 4f).interval(2)
				.ammo(0, 200).traits(FALLOFF, SLOW_ON_HIT).desc("Less damage, but slows targets."));
		add(WeaponDef.of("tomislav", "Tomislav", HEAVY, PRIMARY, MINIGUN).damage(9).pellets(4, 3f).interval(2)
				.ammo(0, 200).traits(FALLOFF, FAST_SPIN).desc("Spins up much faster, tighter spread."));
		add(WeaponDef.of("sandvich", "Sandvich", HEAVY, SECONDARY, CONSUMABLE).interval(600).traits(HEAL_FOOD)
				.desc("Eat to fully heal over a few seconds. Recharges."));
		add(WeaponDef.of("heavy_shotgun", "Shotgun", HEAVY, SECONDARY, HITSCAN).damage(6).pellets(10, 5f).interval(13)
				.ammo(6, 32).reload(10, true).traits(FALLOFF).desc("Reliable 10-pellet shotgun."));
		add(WeaponDef.of("fists", "Fists", HEAVY, MELEE, SWING).damage(65).interval(16).desc("Punch things."));
		add(WeaponDef.of("gru", "Gloves of Running Urgently", HEAVY, MELEE, SWING).damage(48).interval(16).traits(SPEED_WHILE_HELD)
				.desc("Run much faster while held."));

		// ---------------------------------------------------------------- Engineer
		add(WeaponDef.of("engineer_shotgun", "Shotgun", ENGINEER, PRIMARY, HITSCAN).damage(6).pellets(10, 5f).interval(13)
				.ammo(6, 32).reload(10, true).traits(FALLOFF).desc("Reliable 10-pellet shotgun."));
		add(WeaponDef.of("frontier_justice", "Frontier Justice", ENGINEER, PRIMARY, HITSCAN).damage(6).pellets(10, 5f).interval(13)
				.ammo(3, 32).reload(10, true).traits(FALLOFF, REVENGE).desc("Gains a crit for each kill your sentry scores."));
		add(WeaponDef.of("engineer_pistol", "Pistol", ENGINEER, SECONDARY, HITSCAN).damage(15).spread(1.5f).interval(3)
				.ammo(12, 200).reload(25, false).traits(FALLOFF).desc("Rapid semi-automatic sidearm."));
		add(WeaponDef.of("wrench", "Wrench", ENGINEER, MELEE, SWING).damage(65).interval(16)
				.desc("Hit your buildings to repair and upgrade them (uses metal)."));
		add(WeaponDef.of("gunslinger", "Gunslinger", ENGINEER, MELEE, SWING).damage(65).interval(16).traits(EXTRA_HEALTH)
				.desc("+25 max health. Also repairs buildings."));

		// ---------------------------------------------------------------- Medic
		add(WeaponDef.of("syringe_gun", "Syringe Gun", MEDIC, PRIMARY, SYRINGE).damage(10).spread(1.5f).interval(2)
				.ammo(40, 150).reload(32, false).projectile(1.9f, 0).desc("Fires arcing syringes."));
		add(WeaponDef.of("blutsauger", "Blutsauger", MEDIC, PRIMARY, SYRINGE).damage(10).spread(1.5f).interval(2)
				.ammo(40, 150).reload(32, false).projectile(1.9f, 0).traits(HEAL_ON_HIT).desc("Syringes heal you on hit."));
		add(WeaponDef.of("medigun", "Medi Gun", MEDIC, SECONDARY, MEDIGUN).range(10f)
				.desc("Hold left-click on a teammate to heal. Right-click when full to deploy ÜberCharge."));
		add(WeaponDef.of("kritzkrieg", "Kritzkrieg", MEDIC, SECONDARY, MEDIGUN).range(10f).traits(KRITZ)
				.desc("Builds charge 25% faster. Charge grants crits instead of invulnerability."));
		add(WeaponDef.of("bonesaw", "Bonesaw", MEDIC, MELEE, SWING).damage(65).interval(16).desc("Standard melee."));
		add(WeaponDef.of("ubersaw", "Ubersaw", MEDIC, MELEE, SWING).damage(65).interval(19).traits(UBER_ON_HIT)
				.desc("Each hit adds 25% ÜberCharge. Slower swing."));

		// ---------------------------------------------------------------- Sniper
		add(WeaponDef.of("sniper_rifle", "Sniper Rifle", SNIPER, PRIMARY, SCOPED).damage(50).interval(30)
				.ammo(0, 25).range(160f).traits(HEADSHOT).desc("Hold right-click to scope and charge. Headshots crit."));
		add(WeaponDef.of("huntsman", "Huntsman", SNIPER, PRIMARY, BOW).damage(50).interval(10)
				.ammo(1, 12).reload(0, false).projectile(2.6f, 0).traits(HEADSHOT).desc("Hold left-click to draw, release to fire. Headshots crit."));
		add(WeaponDef.of("smg", "SMG", SNIPER, SECONDARY, HITSCAN).damage(8).spread(2.5f).interval(2)
				.ammo(25, 75).reload(22, false).traits(FALLOFF).desc("Fully automatic close-range backup."));
		add(WeaponDef.of("jarate", "Jarate", SNIPER, SECONDARY, JAR).interval(400).projectile(1.2f, 4.5f)
				.desc("Throw to soak enemies: they take mini-crits for 10 s. Extinguishes teammates."));
		add(WeaponDef.of("kukri", "Kukri", SNIPER, MELEE, SWING).damage(65).interval(16).desc("Standard melee."));
		add(WeaponDef.of("tribalmans_shiv", "Tribalman's Shiv", SNIPER, MELEE, SWING).damage(32).interval(16).traits(BLEED)
				.desc("Hits cause bleeding."));

		// ---------------------------------------------------------------- Spy
		add(WeaponDef.of("revolver", "Revolver", SPY, PRIMARY, HITSCAN).damage(40).spread(0.8f).interval(10)
				.ammo(6, 24).reload(23, false).traits(FALLOFF).desc("Heavy-hitting six-shooter."));
		add(WeaponDef.of("ambassador", "Ambassador", SPY, PRIMARY, HITSCAN).damage(34).interval(12)
				.ammo(6, 24).reload(23, false).traits(FALLOFF, HEADSHOT).desc("Headshots crit. Slightly slower."));
		add(WeaponDef.of("sapper", "Sapper", SPY, SECONDARY, SAPPER).range(4f)
				.desc("Left-click an enemy building to disable and destroy it. Also stuns mobs."));
		add(WeaponDef.of("knife", "Knife", SPY, MELEE, SWING).damage(40).interval(16).traits(BACKSTAB)
				.desc("Stab enemies in the back for an instant kill."));
		add(WeaponDef.of("big_earner", "Big Earner", SPY, MELEE, SWING).damage(40).interval(16).traits(BACKSTAB, LESS_HEALTH)
				.desc("Backstabs refill cloak and give a speed burst. -25 max health."));

		// ---------------------------------------------------------------- Engineer PDA (always given)
		add(WeaponDef.of("construction_pda", "Construction PDA", ENGINEER, PDA, BUILDER)
				.desc("Left-click: build a Sentry Gun (130 metal). Right-click: build a Dispenser (100 metal)."));
	}

	public static void register() {
		for (WeaponDef d : DEFS.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Mercenaries.id(d.id()));
			Item.Properties props = new Item.Properties().setId(key).stacksTo(1);
			if (d.kind() == WeaponDef.Kind.SWING && !d.has(PASSIVE)) {
				props.attributes(meleeAttributes(d));
			}
			MercWeaponItem item = new MercWeaponItem(d, props);
			Registry.register(BuiltInRegistries.ITEM, key, item);
			ITEMS.put(d.id(), item);
		}
	}

	private static ItemAttributeModifiers meleeAttributes(WeaponDef d) {
		double dmg = MercConfig.dmg(d.damage()) - 1.0;      // player base attack damage is 1
		double perSecond = 20.0 / Math.max(1, d.interval()); // swings per second
		return ItemAttributeModifiers.builder()
				.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, dmg, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, perSecond - 4.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.build();
	}

	public static WeaponDef get(String id) {
		return DEFS.get(id);
	}

	public static MercWeaponItem item(String id) {
		return ITEMS.get(id);
	}

	public static Iterable<WeaponDef> all() {
		return Collections.unmodifiableCollection(DEFS.values());
	}

	/** Options for a class/slot, in display order (the first one is the stock default). */
	public static List<WeaponDef> options(MercClass c, WeaponDef.Slot slot) {
		List<WeaponDef> out = new ArrayList<>();
		for (WeaponDef d : DEFS.values()) {
			if (d.mercClass() == c && d.slot() == slot) out.add(d);
		}
		// classes that share a weapon from another class's list
		if (c == PYRO && slot == SECONDARY) out.add(0, DEFS.get("shotgun"));
		return out;
	}

	/** True if {@code d} may be equipped by {@code c} in {@code slot}. */
	public static boolean allowed(MercClass c, WeaponDef.Slot slot, WeaponDef d) {
		return d != null && options(c, slot).contains(d);
	}

	public static String defaultFor(MercClass c, WeaponDef.Slot slot) {
		List<WeaponDef> o = options(c, slot);
		return o.isEmpty() ? "" : o.getFirst().id();
	}
}
