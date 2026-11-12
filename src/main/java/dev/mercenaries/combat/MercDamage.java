package dev.mercenaries.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

import dev.mercenaries.Mercenaries;

/**
 * Damage types for merc weapons (data/mercenaries/damage_type/*.json). Both are tagged
 * minecraft:bypasses_cooldown so rapid-fire weapons aren't swallowed by vanilla's i-frames.
 */
public final class MercDamage {
	private MercDamage() {
	}

	public static final ResourceKey<DamageType> BULLET = ResourceKey.create(Registries.DAMAGE_TYPE, Mercenaries.id("bullet"));
	public static final ResourceKey<DamageType> BLAST = ResourceKey.create(Registries.DAMAGE_TYPE, Mercenaries.id("blast"));

	private static DamageSource source(ServerLevel level, ResourceKey<DamageType> key, Entity direct, Entity attacker) {
		return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key), direct, attacker);
	}

	/** Bullets, pellets, flames, syringes, arrows, sentry fire. */
	public static DamageSource bullet(ServerLevel level, Entity direct, Entity attacker) {
		return source(level, BULLET, direct == null ? attacker : direct, attacker);
	}

	/** Rockets, pipes, stickies. */
	public static DamageSource blast(ServerLevel level, Entity direct, Entity attacker) {
		return source(level, BLAST, direct == null ? attacker : direct, attacker);
	}

	/**
	 * Server-side velocity changes on players are not seen by their client unless sent: players move
	 * themselves. Mobs are simulated on the server, so they need nothing.
	 */
	public static void syncMotion(Entity e) {
		if (e instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
	}
}
