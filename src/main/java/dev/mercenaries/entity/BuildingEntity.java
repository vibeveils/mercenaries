package dev.mercenaries.entity;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.mercenaries.MercConfig;
import dev.mercenaries.MercSounds;
import dev.mercenaries.MercTeam;
import dev.mercenaries.combat.Combat;
import dev.mercenaries.data.MercData;

/**
 * Engineer buildings: a sentry gun (shoots hostile mobs and enemy-team players) or a dispenser
 * (heals and resupplies teammates, generates metal). Upgrade to level 3 by hitting it with a wrench.
 */
public class BuildingEntity extends Mob {
	public enum Kind { SENTRY, DISPENSER }

	private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> TEAM = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> LEVEL = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SAPPED = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> UPGRADE = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);

	private UUID owner;
	private LivingEntity target;
	private int cooldown;
	private int rocketCooldown;
	public int kills;

	public BuildingEntity(EntityType<? extends BuildingEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setNoAi(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 150 / 6.25)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.MOVEMENT_SPEED, 0.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(KIND, 0);
		builder.define(TEAM, 0);
		builder.define(LEVEL, 1);
		builder.define(SAPPED, 0);
		builder.define(UPGRADE, 0);
	}

	public void setup(Kind kind, ServerPlayer owner) {
		entityData.set(KIND, kind.ordinal());
		entityData.set(TEAM, MercData.loadout(owner).team());
		this.owner = owner.getUUID();
		applyLevelHealth(true);
	}

	public Kind kind() { return entityData.get(KIND) == 1 ? Kind.DISPENSER : Kind.SENTRY; }
	public MercTeam team() { return MercTeam.byOrdinal(entityData.get(TEAM)); }
	public int buildingLevel() { return entityData.get(LEVEL); }
	public int upgradeProgress() { return entityData.get(UPGRADE); }
	public boolean isSapped() { return entityData.get(SAPPED) > 0; }
	public UUID ownerId() { return owner; }

	public ServerPlayer ownerPlayer() {
		return owner != null && level() instanceof ServerLevel sl && sl.getServer() != null
				? sl.getServer().getPlayerList().getPlayer(owner) : null;
	}

	public void sap(int ticks) {
		entityData.set(SAPPED, Math.max(entityData.get(SAPPED), ticks));
	}

	private void applyLevelHealth(boolean fill) {
		int lvl = buildingLevel();
		double tf2 = new int[]{150, 180, 216}[lvl - 1];
		var attr = getAttribute(Attributes.MAX_HEALTH);
		if (attr != null) attr.setBaseValue(tf2 / MercConfig.get().hpScale());
		if (fill) setHealth(getMaxHealth());
	}

	/** Wrench hit: repair first, then put metal into upgrades. Returns metal spent. */
	public int wrench(int metalAvailable) {
		int spent = 0;
		float missing = getMaxHealth() - getHealth();
		if (missing > 0 && metalAvailable > 0) {
			int cost = Math.min(metalAvailable, Math.min(33, (int) Math.ceil(missing * 6.25 / 3)));
			heal(cost * 3 / 6.25f);
			spent += cost;
			metalAvailable -= cost;
		}
		if (buildingLevel() < 3 && metalAvailable > 0) {
			int add = Math.min(25, metalAvailable);
			int prog = upgradeProgress() + add;
			spent += add;
			if (prog >= 200) {
				entityData.set(LEVEL, buildingLevel() + 1);
				prog = 0;
				applyLevelHealth(true);
				playSound(MercSounds.fx("build"), 1f, 1.2f);
			}
			entityData.set(UPGRADE, prog);
		}
		return spent;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel sl)) {
			if (isSapped() && tickCount % 4 == 0) level().addParticle(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.6, getZ(), 0, 0.1, 0);
			return;
		}
		int sapped = entityData.get(SAPPED);
		if (sapped > 0) {
			entityData.set(SAPPED, sapped - 1);
			if (tickCount % 10 == 0) hurtServer(sl, sl.damageSources().magic(), MercConfig.dmg(25 * 0.5));
			return;
		}
		if (kind() == Kind.SENTRY) tickSentry(sl);
		else tickDispenser(sl);
	}

	private boolean isEnemy(LivingEntity e) {
		if (e == this || !e.isAlive() || e instanceof BuildingEntity b && b.team() == team()) return false;
		if (e instanceof Player p) {
			if (p.isCreative() || p.isSpectator()) return false;
			MercData.Loadout l = MercData.loadout(p);
			return l.active() && l.mercTeam() != team() && !MercData.status(p).cloaked();
		}
		if (e instanceof BuildingEntity b) return b.team() != team();
		return e instanceof Enemy;
	}

	private boolean canSee(LivingEntity e) {
		Vec3 from = position().add(0, 0.7, 0);
		Vec3 to = e.getEyePosition();
		return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
	}

	private void tickSentry(ServerLevel sl) {
		double range = 22;
		if (target != null && (!target.isAlive() || distanceTo(target) > range || !canSee(target) || !isEnemy(target))) target = null;
		if (target == null && tickCount % 5 == 0) {
			target = sl.getEntitiesOfClass(LivingEntity.class, new AABB(blockPosition()).inflate(range), this::isEnemy).stream()
					.filter(this::canSee)
					.min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
			if (target != null) playSound(MercSounds.fx("sentry_spot"), 1f, 1f);
		}
		if (target == null) {
			setYRot(getYRot() + 2f);
			setYHeadRot(getYRot());
			if (tickCount % 60 == 0) playSound(MercSounds.fx("sentry_scan"), 0.6f, 1f);
			return;
		}
		Vec3 d = target.getEyePosition().subtract(position().add(0, 0.7, 0));
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		setYRot(yaw);
		setYHeadRot(yaw);
		setYBodyRot(yaw);
		setXRot((float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG));
		int lvl = buildingLevel();
		if (--cooldown <= 0) {
			cooldown = lvl == 1 ? 5 : 2;
			ServerPlayer op = ownerPlayer();
			LivingEntity attacker = op != null ? op : this;
			double dmg = 16;
			boolean died = Combat.rawDamage(sl, target, sl.damageSources().mobAttack(attacker), MercConfig.dmg(dmg));
			if (died) {
				kills++;
				if (op != null) Combat.onSentryKill(op);
			}
			playSound(MercSounds.fx("sentry_shoot"), 0.8f, 1f);
		}
		if (lvl >= 3 && --rocketCooldown <= 0) {
			rocketCooldown = 60;
			Combat.explosion(sl, this, null, target.position(), 100, 2.5f, false, target, this);
		}
	}

	private void tickDispenser(ServerLevel sl) {
		int lvl = buildingLevel();
		if (tickCount % 10 != 0) return;
		for (Player p : sl.getEntitiesOfClass(Player.class, getBoundingBox().inflate(3.5))) {
			MercData.Loadout l = MercData.loadout(p);
			if (!l.active() || l.mercTeam() != team()) continue;
			p.heal(MercConfig.dmg(new int[]{10, 15, 20}[lvl - 1] * 0.5 * 2)); // TF2 heals per second; we tick every 0.5 s
			if (tickCount % 100 == 0) {
				Combat.resupply(p, 0.2 + 0.1 * lvl);
				var s = MercData.status(p);
				if (l.mercClass() == dev.mercenaries.MercClass.ENGINEER) MercData.setStatus(p, s.withMetal(s.metal() + 40));
			}
			if (p.isOnFire()) p.clearFire();
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.getEntity() instanceof LivingEntity le && !isEnemy(le) && le != this) return false;
		return super.hurtServer(level, source, amount);
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distanceSqr) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("MercKind", entityData.get(KIND));
		output.putInt("MercTeam", entityData.get(TEAM));
		output.putInt("MercLevel", entityData.get(LEVEL));
		if (owner != null) output.store("MercOwner", UUIDUtil.CODEC, owner);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(KIND, input.getIntOr("MercKind", 0));
		entityData.set(TEAM, input.getIntOr("MercTeam", 0));
		entityData.set(LEVEL, Mth.clamp(input.getIntOr("MercLevel", 1), 1, 3));
		Optional<UUID> o = input.read("MercOwner", UUIDUtil.CODEC);
		owner = o.orElse(null);
	}

	public void playFx(String name) {
		level().playSound(null, getX(), getY(), getZ(), MercSounds.fx(name), SoundSource.BLOCKS, 1f, 1f);
	}
}
