package dev.mercenaries.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import dev.mercenaries.MercSounds;
import dev.mercenaries.combat.Combat;
import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.Weapons;

/**
 * All mercenary projectiles share one entity type; the {@link Type} decides behaviour.
 * Rendered with vanilla's ThrownItemRenderer using a hidden "projectile_*" item, which the
 * extracted resource pack swaps for the real 3D projectile mesh.
 *
 * <p>Projectiles are short-lived and not saved; on chunk reload they simply vanish.
 */
public class MercProjectile extends ThrowableItemProjectile {
	public enum Type { ROCKET, PIPE, STICKY, FLARE, ARROW, JAR, SYRINGE }

	private Type type = Type.ROCKET;
	private String weaponId = "";
	private double damage;
	private float radius;
	private boolean crit;
	private int age;
	private boolean stuck;
	private Vec3 stuckPos;
	private boolean configured;

	public MercProjectile(EntityType<? extends MercProjectile> type, Level level) {
		super(type, level);
	}

	public static MercProjectile create(Level level, LivingEntity owner, Type type, WeaponDef def, double damage, boolean crit) {
		MercProjectile p = new MercProjectile(MercEntities.PROJECTILE, level);
		p.setOwner(owner);
		p.type = type;
		p.weaponId = def.id();
		p.damage = damage;
		p.radius = def.radius();
		p.crit = crit;
		p.configured = true;
		p.setItem(new ItemStack(MercEntities.projectileItem(type)));
		Vec3 eye = owner.getEyePosition();
		Vec3 look = owner.getLookAngle();
		p.setPos(eye.x + look.x * 0.4, eye.y - 0.15 + look.y * 0.4, eye.z + look.z * 0.4);
		return p;
	}

	public Type kind() {
		return type;
	}

	public boolean isStuck() {
		return stuck;
	}

	public int age() {
		return age;
	}

	@Override
	protected Item getDefaultItem() {
		return MercEntities.projectileItem(type);
	}

	@Override
	protected double getDefaultGravity() {
		return switch (type) {
			case ROCKET, FLARE -> 0.0;
			case ARROW -> 0.02;
			case SYRINGE -> 0.025;
			default -> 0.05;
		};
	}

	@Override
	public void tick() {
		if (stuck) {
			// Stickies stay put; skip projectile physics.
			if (stuckPos != null) setPos(stuckPos);
			setDeltaMovement(Vec3.ZERO);
			baseTick();
		} else {
			super.tick();
		}
		age++;
		Level level = level();
		if (level.isClientSide()) {
			if (type == Type.ROCKET && age > 1) level.addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0, 0, 0);
			if (type == Type.FLARE) level.addParticle(ParticleTypes.FLAME, getX(), getY(), getZ(), 0, 0, 0);
			return;
		}
		if (!configured) {
			discard();
			return;
		}
		if (getOwner() == null || !getOwner().isAlive() && type == Type.STICKY) {
			discard();
			return;
		}
		if (type == Type.PIPE && age > 46) explode(null);
		if (age > 20 * 20 && type != Type.STICKY) discard();
	}

	@Override
	protected void onHitEntity(EntityHitResult hit) {
		if (level().isClientSide()) return;
		Entity target = hit.getEntity();
		Entity owner = getOwner();
		if (target == owner && age < 5) return;
		if (owner instanceof LivingEntity lo && target instanceof LivingEntity lt && Combat.sameTeam(lo, lt) && type != Type.JAR) {
			return; // fly through teammates
		}
		switch (type) {
			case ROCKET -> explode(target);
			case PIPE -> {
				if (!stuck && age < 46) explode(target);
			}
			case STICKY -> { /* stickies bounce off entities */ }
			case JAR -> splashJar();
			default -> {
				if (owner instanceof LivingEntity lo && target instanceof LivingEntity lt) {
					WeaponDef def = Weapons.get(weaponId);
					boolean headshot = type == Type.ARROW && hit.getLocation().y > lt.getEyeY() - 0.3;
					Combat.directHit(lo, lt, def, damage, crit || headshot, this);
					if (type == Type.FLARE) lt.igniteForSeconds(7);
				}
				discard();
			}
		}
	}

	@Override
	protected void onHitBlock(BlockHitResult hit) {
		super.onHitBlock(hit);
		if (level().isClientSide()) return;
		switch (type) {
			case ROCKET, FLARE -> {
				if (type == Type.ROCKET) explode(null);
				else discard();
			}
			case PIPE -> {
				WeaponDef def = Weapons.get(weaponId);
				if (def != null && def.has(WeaponDef.Trait.NO_BOUNCE)) {
					discard();
					return;
				}
				Vec3 v = getDeltaMovement();
				Vec3 n = dirVec(hit);
				Vec3 r = v.subtract(n.scale(2 * v.dot(n))).scale(0.45);
				setDeltaMovement(r);
				setPos(hit.getLocation().add(n.scale(0.05)));
			}
			case STICKY -> {
				stuck = true;
				stuckPos = hit.getLocation().add(dirVec(hit).scale(0.08));
				setPos(stuckPos);
				setDeltaMovement(Vec3.ZERO);
				setNoGravity(true);
			}
			case JAR -> splashJar();
			default -> discard();
		}
	}

	/** Detonates this explosive. {@code direct} is the entity that was hit dead-on, if any. */
	public void explode(Entity direct) {
		if (isRemoved() || !(level() instanceof ServerLevel sl)) return;
		Entity owner = getOwner();
		WeaponDef def = Weapons.get(weaponId);
		if (owner instanceof LivingEntity lo && def != null) {
			Combat.explosion(sl, lo, def, position(), damage, radius, crit, direct, this);
		}
		discard();
	}

	private void splashJar() {
		if (!(level() instanceof ServerLevel sl)) return;
		if (getOwner() instanceof LivingEntity lo) Combat.jarate(sl, lo, position(), radius);
		sl.playSound(null, getX(), getY(), getZ(), MercSounds.fx("jarate_break"), SoundSource.PLAYERS, 1f, 1f);
		discard();
	}

	/** Airblast reflect: the Pyro becomes the owner and the projectile reverses toward their aim. */
	public void reflect(Player pyro) {
		setOwner(pyro);
		crit = true; // reflected projectiles mini-crit in TF2; we use a full crit flag for simplicity
		stuck = false;
		setNoGravity(false);
		Vec3 look = pyro.getLookAngle();
		double speed = Math.max(0.8, getDeltaMovement().length());
		setDeltaMovement(look.scale(speed));
		age = Math.min(age, 10);
	}

	private static Vec3 dirVec(BlockHitResult hit) {
		var d = hit.getDirection();
		return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
