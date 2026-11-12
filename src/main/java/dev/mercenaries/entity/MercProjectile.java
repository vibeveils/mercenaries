package dev.mercenaries.entity;

import java.util.UUID;

import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.mercenaries.MercSounds;
import dev.mercenaries.combat.Combat;
import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.Weapons;

/**
 * All mercenary projectiles share one entity type; {@link Type} decides behaviour. Built on plain
 * {@link Entity} with its own movement and collision so it doesn't depend on vanilla's projectile
 * class hierarchy (which was reorganised in 26.x). Short-lived and never saved.
 */
public class MercProjectile extends Entity {
	public enum Type { ROCKET, PIPE, STICKY, FLARE, ARROW, JAR, SYRINGE }

	private static final EntityDataAccessor<Integer> TYPE = SynchedEntityData.defineId(MercProjectile.class, EntityDataSerializers.INT);

	private Entity owner;
	private UUID ownerId;
	private String weaponId = "";
	private double damage;
	private float radius;
	private boolean crit;
	private int age;
	private boolean stuck;
	private boolean configured;

	public MercProjectile(EntityType<? extends MercProjectile> type, Level level) {
		super(type, level);
	}

	public static MercProjectile create(Level level, LivingEntity owner, Type type, WeaponDef def, double damage, boolean crit) {
		MercProjectile p = new MercProjectile(MercEntities.PROJECTILE, level);
		p.setOwner(owner);
		p.entityData.set(TYPE, type.ordinal());
		p.weaponId = def.id();
		p.damage = damage;
		p.radius = def.radius();
		p.crit = crit;
		p.configured = true;
		Vec3 eye = owner.getEyePosition();
		Vec3 look = owner.getLookAngle();
		p.setPos(eye.x + look.x * 0.4, eye.y - 0.15 + look.y * 0.4, eye.z + look.z * 0.4);
		return p;
	}

	/** Launches along the given rotation (degrees) at {@code speed} blocks/tick with a random cone spread. */
	public void shoot(Entity shooter, float pitch, float yaw, float speed, float inaccuracyDeg) {
		Vec3 dir = Combat.spread(Vec3.directionFromRotation(pitch, yaw), inaccuracyDeg, shooter.getRandom());
		Vec3 inherit = shooter.getDeltaMovement().multiply(0.5, shooter.onGround() ? 0 : 0.5, 0.5);
		setDeltaMovement(dir.scale(speed).add(inherit));
	}

	// ------------------------------------------------------------------ accessors

	public Type kind() {
		return Type.values()[Math.floorMod(entityData.get(TYPE), Type.values().length)];
	}

	public Entity getOwner() {
		if (owner == null && ownerId != null && level() instanceof ServerLevel sl) owner = sl.getEntity(ownerId);
		return owner;
	}

	public void setOwner(Entity e) {
		owner = e;
		ownerId = e == null ? null : e.getUUID();
	}

	public boolean isStuck() {
		return stuck;
	}

	public int age() {
		return age;
	}

	private double gravity() {
		return switch (kind()) {
			case ROCKET, FLARE -> 0.0;
			case ARROW -> 0.02;
			case SYRINGE -> 0.025;
			default -> 0.05;
		};
	}

	// ------------------------------------------------------------------ movement

	@Override
	public void tick() {
		super.tick();
		age++;
		Level level = level();
		Type type = kind();
		if (level.isClientSide()) {
			if (type == Type.ROCKET && age > 1) level.addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0, 0, 0);
			if (type == Type.FLARE) level.addParticle(ParticleTypes.FLAME, getX(), getY(), getZ(), 0, 0, 0);
			return;
		}
		Entity own = getOwner();
		if (!configured || own == null || type == Type.STICKY && !own.isAlive()) {
			discard();
			return;
		}
		if (type == Type.PIPE && age > 46) {
			explode(null);
			return;
		}
		if (age > 20 * 20 && type != Type.STICKY) {
			discard();
			return;
		}
		if (stuck) {
			setDeltaMovement(Vec3.ZERO);
			return;
		}

		Vec3 v = getDeltaMovement().add(0, -gravity(), 0);
		Vec3 from = position();
		Vec3 to = from.add(v);
		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 end = block.getType() != HitResult.Type.MISS ? block.getLocation() : to;

		Entity hitEntity = null;
		Vec3 hitPos = null;
		double best = Double.MAX_VALUE;
		AABB sweep = getBoundingBox().expandTowards(end.subtract(from)).inflate(0.5);
		for (Entity e : level.getEntities(this, sweep, x -> x instanceof LivingEntity && x.isAlive() && x.isPickable())) {
			if (e == own && age < 5) continue;
			var clip = e.getBoundingBox().inflate(0.3).clip(from, end);
			if (clip.isEmpty()) continue;
			double d = clip.get().distanceToSqr(from);
			if (d < best) {
				best = d;
				hitEntity = e;
				hitPos = clip.get();
			}
		}

		if (hitEntity != null) {
			setPos(hitPos);
			onHitEntity(hitEntity, hitPos);
		} else if (block.getType() == HitResult.Type.BLOCK) {
			setPos(block.getLocation());
			setDeltaMovement(v);
			onHitBlock(block);
		} else {
			setPos(to);
			setDeltaMovement(gravity() > 0 ? v.scale(0.99) : v);
		}
	}

	private void onHitEntity(Entity target, Vec3 at) {
		Entity own = getOwner();
		Type type = kind();
		if (own instanceof LivingEntity lo && target instanceof LivingEntity lt && Combat.sameTeam(lo, lt) && type != Type.JAR) {
			return; // fly through teammates
		}
		switch (type) {
			case ROCKET -> explode(target);
			case PIPE -> explode(target);
			case STICKY -> setDeltaMovement(getDeltaMovement().scale(-0.2)); // stickies bounce off entities
			case JAR -> splashJar();
			default -> {
				if (own instanceof LivingEntity lo && target instanceof LivingEntity lt) {
					WeaponDef def = Weapons.get(weaponId);
					boolean headshot = type == Type.ARROW && at.y > lt.getEyeY() - 0.3;
					Combat.directHit(lo, lt, def, damage, crit || headshot, this);
					if (type == Type.FLARE) lt.igniteForSeconds(7);
				}
				discard();
			}
		}
	}

	private void onHitBlock(BlockHitResult hit) {
		Direction face = hit.getDirection();
		Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		switch (kind()) {
			case ROCKET -> explode(null);
			case PIPE -> {
				WeaponDef def = Weapons.get(weaponId);
				if (def != null && def.has(WeaponDef.Trait.NO_BOUNCE)) {
					discard();
					return;
				}
				Vec3 v = getDeltaMovement();
				setDeltaMovement(v.subtract(n.scale(2 * v.dot(n))).scale(0.45));
				setPos(hit.getLocation().add(n.scale(0.05)));
			}
			case STICKY -> {
				stuck = true;
				setPos(hit.getLocation().add(n.scale(0.08)));
				setDeltaMovement(Vec3.ZERO);
			}
			case JAR -> splashJar();
			default -> discard();
		}
	}

	/** Detonates this explosive. {@code direct} is the entity that was hit dead-on, if any. */
	public void explode(Entity direct) {
		if (isRemoved() || !(level() instanceof ServerLevel sl)) return;
		WeaponDef def = Weapons.get(weaponId);
		if (getOwner() instanceof LivingEntity lo && def != null) {
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
		crit = true;
		stuck = false;
		double speed = Math.max(0.8, getDeltaMovement().length());
		setDeltaMovement(pyro.getLookAngle().scale(speed));
		age = Math.min(age, 10);
	}

	// ------------------------------------------------------------------ Entity plumbing

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(TYPE, 0);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		entityData.set(TYPE, input.getIntOr("MercType", 0));
		ownerId = input.read("MercOwner", UUIDUtil.CODEC).orElse(null);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("MercType", entityData.get(TYPE));
		if (ownerId != null) output.store("MercOwner", UUIDUtil.CODEC, ownerId);
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
