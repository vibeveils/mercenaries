package dev.mercenaries.combat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.mercenaries.MercClass;
import dev.mercenaries.MercConfig;
import dev.mercenaries.MercSounds;
import dev.mercenaries.MercTeam;
import dev.mercenaries.data.MercData;
import dev.mercenaries.entity.BuildingEntity;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.WeaponDef.Trait;
import dev.mercenaries.weapon.Weapons;

/** Shared combat rules: teams, crits, explosions, hitscan and the damage-modifier pipeline. */
public final class Combat {
	private Combat() {
	}

	/** Entities soaked by Jarate (take mini-crits) -> game time the effect ends. */
	private static final Map<UUID, Long> JARATED = new ConcurrentHashMap<>();
	/** Players under Bonk! -> game time the effect ends. */
	private static final Map<UUID, Long> BONKED = new ConcurrentHashMap<>();
	/** Players currently launched by their own explosion (reduced fall damage until they land). */
	private static final Map<UUID, Boolean> BLAST_JUMPING = new ConcurrentHashMap<>();
	/** Revenge crits banked by Frontier Justice. */
	private static final Map<UUID, Integer> REVENGE = new ConcurrentHashMap<>();

	private static final ThreadLocal<Boolean> REENTRANT = ThreadLocal.withInitial(() -> false);

	// ------------------------------------------------------------------ teams

	public static MercTeam teamOf(Entity e) {
		if (e instanceof BuildingEntity b) return b.team();
		if (e instanceof Player p) {
			MercData.Loadout l = MercData.loadout(p);
			return l.active() ? l.mercTeam() : null;
		}
		return null;
	}

	public static boolean sameTeam(Entity a, Entity b) {
		if (a == null || b == null) return false;
		MercTeam ta = teamOf(a), tb = teamOf(b);
		return ta != null && ta == tb;
	}

	/** Whether {@code attacker}'s merc weapons may damage {@code target}. */
	public static boolean canHurt(LivingEntity attacker, Entity target) {
		if (!(target instanceof LivingEntity lt) || !lt.isAlive() || target == attacker) return false;
		if (sameTeam(attacker, target)) return false;
		if (target instanceof Player p) {
			if (p.isCreative() || p.isSpectator()) return false;
			if (teamOf(p) == null && !MercConfig.get().hurtVanillaPlayers) return false;
		}
		return true;
	}

	// ------------------------------------------------------------------ crit state

	public static boolean jarated(LivingEntity e) {
		Long until = JARATED.get(e.getUUID());
		return until != null && until > e.level().getGameTime();
	}

	public static boolean bonked(LivingEntity e) {
		Long until = BONKED.get(e.getUUID());
		return until != null && until > e.level().getGameTime();
	}

	public static void bonk(Player p, int ticks) {
		BONKED.put(p.getUUID(), p.level().getGameTime() + ticks);
	}

	public static boolean ubered(LivingEntity e) {
		MercData.Status s = MercData.status(e);
		return s.uberTicks() > 0 && !s.kritz();
	}

	public static boolean kritzed(LivingEntity e) {
		MercData.Status s = MercData.status(e);
		return s.uberTicks() > 0 && s.kritz();
	}

	public static void markBlastJump(Player p) {
		BLAST_JUMPING.put(p.getUUID(), true);
	}

	public static void landed(Player p) {
		BLAST_JUMPING.remove(p.getUUID());
	}

	public static void onSentryKill(ServerPlayer engineer) {
		REVENGE.merge(engineer.getUUID(), 1, (a, b) -> Math.min(35, a + b));
	}

	public static int revengeCrits(Player p) {
		return REVENGE.getOrDefault(p.getUUID(), 0);
	}

	public static boolean consumeRevenge(Player p) {
		Integer n = REVENGE.get(p.getUUID());
		if (n == null || n <= 0) return false;
		REVENGE.put(p.getUUID(), n - 1);
		return true;
	}

	/** Returns the multiplier for damage dealt by attacker to target (crits 3x, mini-crits 1.35x). */
	public static double critMultiplier(LivingEntity attacker, LivingEntity target, boolean forcedCrit) {
		if (forcedCrit || kritzed(attacker)) return 3.0;
		if (jarated(target)) return 1.35;
		return 1.0;
	}

	// ------------------------------------------------------------------ applying damage

	/**
	 * Deals damage ignoring vanilla i-frames (rapid-fire weapons would otherwise miss 9 of 10 hits).
	 * Returns true if the target died.
	 */
	public static boolean rawDamage(ServerLevel level, LivingEntity target, DamageSource source, float amount) {
		if (amount <= 0) return false;
		target.invulnerableTime = 0;
		boolean wasAlive = target.isAlive();
		target.hurtServer(level, source, amount);
		return wasAlive && !target.isAlive();
	}

	/** A direct (non-splash) hit from a bullet, syringe, flare, arrow or flame tick. */
	public static void directHit(LivingEntity attacker, LivingEntity target, WeaponDef def, double tf2Damage, boolean crit, Entity direct) {
		if (!(attacker.level() instanceof ServerLevel sl) || !canHurt(attacker, target)) return;
		if (def != null && def.has(Trait.CRIT_BURNING) && target.isOnFire()) crit = true;
		double mult = critMultiplier(attacker, target, crit);
		DamageSource src = attacker instanceof Player p
				? (direct != null && direct != attacker ? sl.damageSources().thrown(direct, attacker) : sl.damageSources().playerAttack(p))
				: sl.damageSources().mobAttack(attacker);
		float amount = MercConfig.dmg(tf2Damage * mult);
		boolean died = rawDamage(sl, target, src, amount);
		if (mult >= 3.0) {
			sl.sendParticles(ParticleTypes.CRIT, target.getX(), target.getEyeY(), target.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
			sl.playSound(null, target.getX(), target.getY(), target.getZ(), MercSounds.fx("crit"), SoundSource.PLAYERS, 0.8f, 1f);
		}
		if (def != null) onHitEffects(attacker, target, def, amount);
	}

	static void onHitEffects(LivingEntity attacker, LivingEntity target, WeaponDef def, float dealt) {
		if (def.has(Trait.HEAL_ON_HIT)) attacker.heal(def.kind() == WeaponDef.Kind.SYRINGE ? MercConfig.dmg(3) : dealt * 0.2f);
		if (def.has(Trait.SLOW_ON_HIT)) target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 1));
		if (def.has(Trait.IGNITE)) target.igniteForSeconds(def.kind() == WeaponDef.Kind.FLAMETHROWER ? 6 : 7);
		if (def.has(Trait.BLEED)) target.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 0));
		if (def.has(Trait.UBER_ON_HIT) && attacker instanceof Player p) {
			MercData.Status s = MercData.status(p);
			MercData.setStatus(p, s.withUber(Math.min(1f, s.uber() + 0.25f)));
		}
	}

	/** Splash damage with TF2-style falloff (100% at center to 50% at the edge) and knockback. */
	public static void explosion(ServerLevel level, LivingEntity attacker, WeaponDef def, Vec3 at, double tf2Damage, float radius,
								 boolean crit, Entity direct, Entity source) {
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.02);
		level.playSound(null, at.x, at.y, at.z, MercSounds.fx("explode"), SoundSource.PLAYERS, 1.5f, 0.9f + level.random.nextFloat() * 0.2f);
		if (MercConfig.get().explosionsBreakBlocks) {
			level.explode(source, at.x, at.y, at.z, Math.min(radius, 3f) * 0.6f, Level.ExplosionInteraction.TNT);
		}
		float r = Math.max(radius, 0.5f);
		List<LivingEntity> hits = level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r));
		boolean airborneMini = def != null && def.has(Trait.MINICRIT_AIRBORNE);
		for (LivingEntity e : hits) {
			Vec3 c = e.getBoundingBox().getCenter();
			double dist = Math.max(0, c.distanceTo(at) - e.getBbWidth() * 0.5);
			if (dist > r && e != direct) continue;
			if (e != direct && level.clip(new ClipContext(at, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).getType() != HitResult.Type.MISS) {
				continue;
			}
			double falloff = e == direct ? 1.0 : 1.0 - 0.5 * Math.min(1, dist / r);
			Vec3 push = c.subtract(at).normalize();
			if (push.lengthSqr() < 1e-6) push = new Vec3(0, 1, 0);
			if (e == attacker) {
				// self damage and the all-important blast jump
				double selfMul = attacker instanceof Player p ? selfBlastMultiplier(p) : 1.0;
				float dmg = MercConfig.dmg(tf2Damage * falloff * selfMul * 0.6);
				if (!ubered(e) && !bonked(e)) rawDamage(level, e, level.damageSources().explosion(source, attacker), dmg);
				double strength = 1.15 * falloff * (e.onGround() ? 1.0 : 1.25);
				e.push(push.x * strength, Math.max(0.35, push.y) * strength + 0.25, push.z * strength);
				e.hurtMarked = true;
				if (e instanceof Player p) markBlastJump(p);
				continue;
			}
			if (!canHurt(attacker, e)) continue;
			boolean mini = airborneMini && !e.onGround();
			double mult = mini && !crit ? 1.35 : critMultiplier(attacker, e, crit);
			float dmg = MercConfig.dmg(tf2Damage * falloff * mult);
			rawDamage(level, e, level.damageSources().explosion(source, attacker), dmg);
			double kb = 0.6 * falloff * (1.0 - e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE));
			e.push(push.x * kb, push.y * kb + 0.15, push.z * kb);
			e.hurtMarked = true;
			if (def != null) onHitEffects(attacker, e, def, dmg);
		}
	}

	static double selfBlastMultiplier(Player p) {
		MercData.Loadout l = MercData.loadout(p);
		double m = 1.0;
		if ("gunboats".equals(l.secondary())) m *= 0.4;
		return m;
	}

	public static void jarate(ServerLevel level, LivingEntity thrower, Vec3 at, float radius) {
		level.sendParticles(ParticleTypes.FALLING_HONEY, at.x, at.y, at.z, 40, radius * 0.4, 0.5, radius * 0.4, 0.1);
		long until = level.getGameTime() + 200;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius))) {
			if (sameTeam(thrower, e) || e == thrower) {
				e.clearFire();
			} else if (canHurt(thrower, e)) {
				JARATED.put(e.getUUID(), until);
				e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false));
			}
		}
	}

	/** Refills a fraction of every carried weapon's ammo. */
	public static void resupply(Player p, double fraction) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.getItem() instanceof MercWeaponItem w && w.def().usesAmmo()) {
				WeaponDef d = w.def();
				int add = (int) Math.ceil((d.clip() + d.reserve()) * fraction);
				int reserve = Math.min(d.reserve(), MercWeaponItem.reserve(s) + add);
				MercWeaponItem.setAmmo(s, MercWeaponItem.clip(s), reserve);
			}
		}
	}

	// ------------------------------------------------------------------ hitscan

	public record Hit(LivingEntity entity, Vec3 pos, double distance) {
	}

	/** Ray from eye along dir; returns the first living entity hit before any block, or null. */
	public static Hit raycast(LivingEntity shooter, Vec3 from, Vec3 dir, double range) {
		Level level = shooter.level();
		Vec3 to = from.add(dir.scale(range));
		var block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
		if (block.getType() != HitResult.Type.MISS) to = block.getLocation();
		AABB area = new AABB(from, to).inflate(1.0);
		Hit best = null;
		for (Entity e : level.getEntities(shooter, area, x -> x instanceof LivingEntity && x.isAlive() && x.isPickable())) {
			AABB bb = e.getBoundingBox().inflate(0.15);
			var clip = bb.clip(from, to);
			if (clip.isEmpty() && !bb.contains(from)) continue;
			Vec3 p = clip.orElse(from);
			double d = p.distanceTo(from);
			if (best == null || d < best.distance) best = new Hit((LivingEntity) e, p, d);
		}
		return best;
	}

	/** Applies a random cone spread (degrees) to a direction. */
	public static Vec3 spread(Vec3 dir, float degrees, net.minecraft.util.RandomSource rnd) {
		if (degrees <= 0) return dir;
		double rad = Math.toRadians(degrees);
		double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * Math.tan(rad);
		Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 right = dir.cross(up).normalize();
		Vec3 up2 = right.cross(dir).normalize();
		return dir.add(right.scale(Math.cos(a) * r)).add(up2.scale(Math.sin(a) * r)).normalize();
	}

	/** TF2 ramp-up/fall-off: 150% point-blank, 100% at ~11 blocks, 50% at 22+ blocks. */
	public static double falloff(double distance) {
		double x = Math.min(distance, 22) / 22.0;
		return 1.5 - x;
	}

	public static boolean isBehind(LivingEntity attacker, LivingEntity target) {
		Vec3 toTarget = target.position().subtract(attacker.position()).multiply(1, 0, 1).normalize();
		Vec3 facing = Vec3.directionFromRotation(0, target.getYRot()).multiply(1, 0, 1).normalize();
		return toTarget.dot(facing) > 0.3;
	}

	// ------------------------------------------------------------------ damage pipeline (ALLOW_DAMAGE)

	/**
	 * Called from ServerLivingEntityEvents.ALLOW_DAMAGE. Returns false to cancel the original damage
	 * (possibly after re-applying a modified amount).
	 */
	public static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
		if (REENTRANT.get()) return true;
		// Über, Bonk!
		if ((ubered(victim) || bonked(victim)) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;

		Entity attackerE = source.getEntity();
		if (attackerE instanceof LivingEntity attacker && attacker != victim && sameTeam(attacker, victim)) return false;

		float modified = amount;
		// Pyro: fire resistance
		if (victim instanceof Player vp) {
			MercData.Loadout vl = MercData.loadout(vp);
			if (vl.mercClass() == MercClass.PYRO && source.is(DamageTypeTags.IS_FIRE)) modified *= 0.25f;
			if (source.is(DamageTypeTags.IS_FALL) && BLAST_JUMPING.containsKey(vp.getUUID())) {
				modified *= vl.mercClass() == MercClass.SOLDIER || vl.mercClass() == MercClass.DEMOMAN ? 0.25f : 0.5f;
			}
			if (source.is(DamageTypeTags.IS_FALL) && vl.mercClass() == MercClass.SCOUT) modified *= 0.6f;
		}

		// Melee specials (vanilla attack with a merc melee weapon)
		if (attackerE instanceof Player ap && source.getDirectEntity() == ap && !source.is(DamageTypeTags.IS_EXPLOSION)) {
			ItemStack held = ap.getMainHandItem();
			if (held.getItem() instanceof MercWeaponItem w && w.def().kind() == WeaponDef.Kind.SWING) {
				WeaponDef d = w.def();
				if (!MercData.status(ap).cloaked() && !bonked(ap) && classMatches(ap, d)) {
					boolean crit = false;
					if (d.has(Trait.BACKSTAB) && isBehind(ap, victim)) {
						modified = Math.max(modified, victim.getHealth() * 6 + 1);
						victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), MercSounds.fx("backstab"), SoundSource.PLAYERS, 1f, 1f);
						if (d.id().equals("big_earner")) {
							MercData.setStatus(ap, MercData.status(ap).withCloak(Math.min(1f, MercData.status(ap).cloak() + 0.3f), false));
							ap.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1));
						}
					}
					if (d.has(Trait.CRIT_BURNING) && victim.isOnFire()) crit = true;
					if (d.has(Trait.LOW_HEALTH_BONUS)) {
						float frac = ap.getHealth() / ap.getMaxHealth();
						modified *= (float) (1.0 + 2.0 * (1.0 - frac));
					}
					modified *= (float) critMultiplier(ap, victim, crit);
					onHitEffects(ap, victim, d, modified);
					victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), MercSounds.fx("melee_hit"), SoundSource.PLAYERS, 0.7f, 1f);
				} else if (!classMatches(ap, d)) {
					modified = 1f;
				}
				// Wrench on own building: repair/upgrade instead of damage
				if (victim instanceof BuildingEntity b && (d.id().equals("wrench") || d.id().equals("gunslinger")) && b.team() == teamOf(ap)) {
					MercData.Status s = MercData.status(ap);
					int spent = b.wrench(s.metal());
					MercData.setStatus(ap, s.withMetal(s.metal() - spent));
					return false;
				}
			}
		}
		if (MercData.status(victim).cloaked()) modified *= 0.8f;

		if (Math.abs(modified - amount) > 1e-4 && victim.level() instanceof ServerLevel sl) {
			REENTRANT.set(true);
			try {
				victim.hurtServer(sl, source, modified);
			} finally {
				REENTRANT.set(false);
			}
			return false;
		}
		return true;
	}

	public static boolean classMatches(Player p, WeaponDef d) {
		MercData.Loadout l = MercData.loadout(p);
		return l.active() && (d.mercClass() == l.mercClass() || Weapons.allowed(l.mercClass(), d.slot(), d));
	}
}
