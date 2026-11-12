package dev.mercenaries;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.Weapons;

/**
 * Sound events. The mod's own sounds.json maps every event to a vanilla sound; the extracted TF2
 * resource pack replaces them with the real game sounds ("replace": true).
 */
public final class MercSounds {
	private static final Map<String, SoundEvent> EVENTS = new HashMap<>();

	public static final String[] FX = {
			"explode", "sticky_detonate", "minigun_spin", "minigun_windup", "flame_loop", "airblast", "medigun_heal",
			"uber", "crit", "backstab", "headshot", "cloak", "uncloak", "melee_hit", "melee_world", "sentry_shoot",
			"sentry_scan", "sentry_spot", "build", "reload", "sniper_charge", "jarate_break", "eat", "drink"
	};

	private MercSounds() {
	}

	private static SoundEvent reg(String path) {
		Identifier id = Mercenaries.id(path);
		SoundEvent e = Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
		EVENTS.put(path, e);
		return e;
	}

	/** Per-weapon events; the extracted pack fills them from TF2's own weapon scripts. */
	public static final String[] WEAPON_KEYS = {"fire", "reload", "hit", "hit_world", "deploy", "empty"};
	/** Per-class voice events (vo/<class>_*.mp3 in TF2). */
	public static final String[] VOICE_KEYS = {"pain", "death", "medic", "battlecry"};

	public static void register() {
		for (WeaponDef d : Weapons.all()) for (String k : WEAPON_KEYS) reg("weapon." + d.id() + "." + k);
		for (String fx : FX) reg("fx." + fx);
		for (MercClass c : MercClass.values()) {
			if (c != MercClass.NONE) for (String k : VOICE_KEYS) reg("class." + c.id() + "." + k);
		}
	}

	public static SoundEvent fire(WeaponDef d) {
		return EVENTS.get("weapon." + d.id() + ".fire");
	}

	public static SoundEvent weapon(WeaponDef d, String key) {
		return EVENTS.get("weapon." + d.id() + "." + key);
	}

	public static SoundEvent voice(MercClass c, String key) {
		return EVENTS.get("class." + c.id() + "." + key);
	}

	public static SoundEvent fx(String name) {
		return EVENTS.get("fx." + name);
	}
}
