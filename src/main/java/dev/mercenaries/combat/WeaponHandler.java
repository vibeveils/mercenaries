package dev.mercenaries.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.mercenaries.MercClass;
import dev.mercenaries.MercConfig;
import dev.mercenaries.MercSounds;
import dev.mercenaries.Mercenaries;
import dev.mercenaries.data.MercData;
import dev.mercenaries.entity.BuildingEntity;
import dev.mercenaries.entity.MercEntities;
import dev.mercenaries.entity.MercProjectile;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.WeaponDef.Kind;
import dev.mercenaries.weapon.WeaponDef.Trait;

/** Server-authoritative weapon firing, driven by the mouse state each client reports. */
public final class WeaponHandler {
	private WeaponHandler() {
	}

	private static final class State {
		boolean primary, secondary, prevPrimary, prevSecondary;
		long nextFire;
		long reloadAt;          // next reload step, 0 = not reloading
		float spin;             // minigun 0..1
		float charge;           // sniper / huntsman / medigun build 0..1
		UUID healTarget;
		int lastFiredTick;
		boolean scoped;
		long consumeUntil;
	}

	private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
	private static final net.minecraft.resources.Identifier SPEED_MOD = Mercenaries.id("weapon_speed");

	private static State state(Player p) {
		return STATES.computeIfAbsent(p.getUUID(), k -> new State());
	}

	public static void clear(Player p) {
		STATES.remove(p.getUUID());
	}

	public static void setInput(ServerPlayer p, boolean primary, boolean secondary) {
		State s = state(p);
		s.primary = primary;
		s.secondary = secondary;
	}

	public static void requestReload(ServerPlayer p) {
		ItemStack held = p.getMainHandItem();
		if (held.getItem() instanceof MercWeaponItem w) startReload(p, state(p), w.def(), held);
	}

	public static boolean isScoped(Player p) {
		State s = STATES.get(p.getUUID());
		return s != null && s.scoped;
	}

	// ------------------------------------------------------------------ per tick

	public static void tick(ServerPlayer p) {
		MercData.Loadout l = MercData.loadout(p);
		if (!l.active() || p.isSpectator()) return;
		ServerLevel level = (ServerLevel) p.level();
		long now = level.getGameTime();
		State st = state(p);
		MercData.Status status = MercData.status(p);

		passives(p, l, status, now);
		status = MercData.status(p);

		ItemStack held = p.getMainHandItem();
		WeaponDef def = held.getItem() instanceof MercWeaponItem w ? w.def() : null;
		float speedMod = 0f;

		if (def != null && Combat.classMatches(p, def) && !status.cloaked() && !Combat.bonked(p)) {
			speedMod = handleWeapon(p, level, st, def, held, now);
		} else {
			st.spin = 0;
			st.scoped = false;
			st.healTarget = null;
			if (def == null || def.kind() != Kind.SCOPED && def.kind() != Kind.BOW) st.charge = 0;
		}
		if (def != null && def.has(Trait.SPEED_WHILE_HELD)) speedMod = 0.3f;

		// weapon movement modifiers (minigun spin, scope)
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			AttributeModifier cur = speed.getModifier(SPEED_MOD);
			if (speedMod == 0f) {
				if (cur != null) speed.removeModifier(SPEED_MOD);
			} else if (cur == null || Math.abs(cur.amount() - speedMod) > 1e-3) {
				speed.addOrReplacePermanentModifier(new AttributeModifier(SPEED_MOD, speedMod, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		}

		float shownCharge = def == null ? 0 : switch (def.kind()) {
			case MINIGUN -> st.spin;
			case SCOPED, BOW -> st.charge;
			default -> 0f;
		};
		MercData.setStatus(p, MercData.status(p).withCharge(Math.round(shownCharge * 50f) / 50f));
		st.prevPrimary = st.primary;
		st.prevSecondary = st.secondary;
	}

	private static void passives(ServerPlayer p, MercData.Loadout l, MercData.Status s, long now) {
		if (p.onGround()) Combat.landed(p);
		// Über countdown
		if (s.uberTicks() > 0) s = s.withUberTicks(s.uberTicks() - 1, s.kritz());
		// Medic regen: ~3 TF2 HP/s
		if (l.mercClass() == MercClass.MEDIC && now % 20 == 0) p.heal(MercConfig.dmg(3));
		// Spy cloak
		if (l.mercClass() == MercClass.SPY) {
			if (s.cloaked()) {
				float c = s.cloak() - 1f / (20 * 9);
				if (c <= 0) {
					s = s.withCloak(0, false);
					uncloak(p);
				} else {
					s = s.withCloak(c, true);
					p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10, 0, false, false, false));
				}
			} else if (s.cloak() < 1f) {
				s = s.withCloak(Math.min(1f, s.cloak() + 1f / (20 * 30)), false);
			}
		}
		// Metal / consumable cooldown
		if (l.mercClass() == MercClass.ENGINEER && now % 100 == 0) s = s.withMetal(s.metal() + MercConfig.get().passiveMetalRegen);
		if (s.cooldown() < 1f) {
			ItemStack sec = findWeapon(p, l.secondary());
			int recharge = 600;
			if (sec != null && sec.getItem() instanceof MercWeaponItem w) recharge = w.def().fireInterval();
			s = s.withCooldown(Math.min(1f, s.cooldown() + 1f / Math.max(20, recharge)));
		}
		// Passive ammo regen so survival play doesn't run dry
		State st = state(p);
		if (now % 100 == 0 && now - st.lastFiredTick > 100 && MercConfig.get().passiveAmmoRegenPercent > 0) {
			Combat.resupply(p, MercConfig.get().passiveAmmoRegenPercent / 100.0);
		}
		MercData.setStatus(p, s);
	}

	private static ItemStack findWeapon(Player p, String id) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.getItem() instanceof MercWeaponItem w && w.def().id().equals(id)) return s;
		}
		return null;
	}

	// ------------------------------------------------------------------ weapon logic

	private static float handleWeapon(ServerPlayer p, ServerLevel level, State st, WeaponDef def, ItemStack held, long now) {
		boolean pressedP = st.primary && !st.prevPrimary;
		boolean pressedS = st.secondary && !st.prevSecondary;
		boolean releasedP = !st.primary && st.prevPrimary;

		// reloading
		if (st.reloadAt > 0) {
			if (st.primary && def.reloadSingly() && MercWeaponItem.clip(held) > 0) {
				st.reloadAt = 0; // interrupt shell-by-shell reload to fire
			} else if (now >= st.reloadAt) {
				int clip = MercWeaponItem.clip(held), res = MercWeaponItem.reserve(held);
				int add = def.reloadSingly() ? Math.min(1, res) : Math.min(def.clip() - clip, res);
				MercWeaponItem.setAmmo(held, clip + add, res - add);
				if (def.reloadSingly() && clip + add < def.clip() && res - add > 0) st.reloadAt = now + def.reloadTicks();
				else st.reloadAt = 0;
				return 0;
			} else {
				return 0;
			}
		}

		switch (def.kind()) {
			case MINIGUN -> {
				boolean spinning = st.secondary || st.primary;
				float rate = def.has(Trait.FAST_SPIN) ? 1f / 7f : 1f / 17f;
				st.spin = spinning ? Math.min(1f, st.spin + rate) : Math.max(0f, st.spin - 0.08f);
				if (spinning && now % 20 == 0) play(p, "minigun_spin", 0.6f);
				if (st.spin > 0.05f && st.spin < 0.2f && spinning && now % 10 == 0) play(p, "minigun_windup", 0.6f);
				if (st.primary && st.spin >= 1f && now >= st.nextFire) {
					if (!consumeAmmo(held, def)) return -0.45f;
					fireHitscan(p, level, def, false);
					st.nextFire = now + def.fireInterval();
					st.lastFiredTick = (int) now;
				}
				return spinning ? -0.45f : 0f;
			}
			case FLAMETHROWER -> {
				if (st.primary && now >= st.nextFire && consumeAmmo(held, def)) {
					fireFlames(p, level, def);
					st.nextFire = now + def.fireInterval();
					st.lastFiredTick = (int) now;
				}
				if (pressedS && !def.has(Trait.NO_AIRBLAST) && MercWeaponItem.reserve(held) >= 20 && now >= st.nextFire) {
					MercWeaponItem.setAmmo(held, 0, MercWeaponItem.reserve(held) - 20);
					airblast(p, level);
					st.nextFire = now + 15;
				}
				return 0f;
			}
			case SCOPED -> {
				st.scoped = st.secondary;
				if (st.scoped) {
					if (st.charge == 0 && now >= st.nextFire) play(p, "sniper_charge", 0.6f);
					if (now >= st.nextFire) st.charge = Math.min(1f, st.charge + 1f / 66f);
				} else {
					st.charge = 0;
				}
				if (pressedP && now >= st.nextFire && consumeAmmo(held, def)) {
					fireSniper(p, level, def, st.scoped, st.charge);
					st.nextFire = now + def.fireInterval();
					st.charge = 0;
					st.lastFiredTick = (int) now;
				}
				return st.scoped ? -0.73f : 0f;
			}
			case BOW -> {
				if (st.primary && now >= st.nextFire && MercWeaponItem.reserve(held) > 0) {
					st.charge = Math.min(1f, st.charge + 1f / 20f);
				}
				if (releasedP && st.charge > 0) {
					if (consumeAmmo(held, def)) {
						double dmg = def.damage() + 70 * st.charge;
						MercProjectile proj = MercProjectile.create(level, p, MercProjectile.Type.ARROW, def, dmg, Combat.kritzed(p));
						proj.shootFromRotation(p, p.getXRot(), p.getYRot(), 0, 1.8f + 0.8f * st.charge, (1 - st.charge) * 2f);
						level.addFreshEntity(proj);
						play(p, def);
					}
					st.charge = 0;
					st.nextFire = now + def.fireInterval();
					st.lastFiredTick = (int) now;
				}
				return st.charge > 0 ? -0.5f : 0f;
			}
			case MEDIGUN -> {
				tickMedigun(p, level, st, def, now, pressedS);
				return 0f;
			}
			case CONSUMABLE -> {
				MercData.Status s = MercData.status(p);
				if (pressedP && s.cooldown() >= 1f) {
					MercData.setStatus(p, s.withCooldown(0f));
					if (def.has(Trait.HEAL_FOOD)) {
						p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 3));
						p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 3));
						play(p, "eat", 1f);
					} else if (def.has(Trait.BONK)) {
						Combat.bonk(p, 160);
						p.addEffect(new MobEffectInstance(MobEffects.SPEED, 160, 0));
						p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1, false, true));
						play(p, "drink", 1f);
					}
				}
				return 0f;
			}
			case BUILDER -> {
				if (pressedP) build(p, level, BuildingEntity.Kind.SENTRY, 130);
				if (pressedS) build(p, level, BuildingEntity.Kind.DISPENSER, 100);
				return 0f;
			}
			case SAPPER -> {
				if (pressedP) sap(p, level, def);
				return 0f;
			}
			case JAR -> {
				MercData.Status s = MercData.status(p);
				if (pressedP && s.cooldown() >= 1f) {
					MercData.setStatus(p, s.withCooldown(0f));
					MercProjectile proj = MercProjectile.create(level, p, MercProjectile.Type.JAR, def, 0, false);
					proj.shootFromRotation(p, p.getXRot(), p.getYRot(), 0, def.projectileSpeed(), 0.5f);
					level.addFreshEntity(proj);
				}
				return 0f;
			}
			case STICKY -> {
				if (pressedS) detonateStickies(p, level, def);
				if (st.primary && now >= st.nextFire) {
					if (!consumeAmmo(held, def)) {
						startReload(p, st, def, held);
						return 0f;
					}
					List<MercProjectile> mine = stickies(p, level);
					if (mine.size() >= 8) mine.stream().min(Comparator.comparingInt(x -> -x.age())).ifPresent(MercProjectile::discard);
					MercProjectile proj = MercProjectile.create(level, p, MercProjectile.Type.STICKY, def, def.damage(), Combat.kritzed(p));
					proj.shootFromRotation(p, p.getXRot(), p.getYRot(), 0, def.projectileSpeed(), 0.5f);
					level.addFreshEntity(proj);
					play(p, def);
					st.nextFire = now + def.fireInterval();
					st.lastFiredTick = (int) now;
				}
				MercData.setStatus(p, MercData.status(p).withStickies(stickies(p, level).size()));
				return 0f;
			}
			case SWING -> {
				return 0f;
			}
			default -> {
				// HITSCAN and projectile launchers
				boolean auto = def.fireInterval() <= 3 || def.kind() != Kind.HITSCAN || def.pellets() > 1;
				boolean wantFire = auto ? st.primary : pressedP;
				if (wantFire && now >= st.nextFire) {
					if (!consumeAmmo(held, def)) {
						startReload(p, st, def, held);
						return 0f;
					}
					switch (def.kind()) {
						case HITSCAN -> fireHitscan(p, level, def, def.has(Trait.REVENGE) && Combat.consumeRevenge(p));
						case ROCKET -> launch(p, level, def, MercProjectile.Type.ROCKET, 0f);
						case GRENADE -> launch(p, level, def, MercProjectile.Type.PIPE, 1f);
						case FLARE -> launch(p, level, def, MercProjectile.Type.FLARE, 0f);
						case SYRINGE -> launch(p, level, def, MercProjectile.Type.SYRINGE, def.spread());
						default -> {
						}
					}
					st.nextFire = now + def.fireInterval();
					st.lastFiredTick = (int) now;
					if (def.clip() > 0 && MercWeaponItem.clip(held) == 0) startReload(p, st, def, held);
				}
				return 0f;
			}
		}
	}

	private static boolean consumeAmmo(ItemStack held, WeaponDef def) {
		if (!def.usesAmmo()) return true;
		int clip = MercWeaponItem.clip(held), res = MercWeaponItem.reserve(held);
		if (def.clip() > 0) {
			if (clip <= 0) return false;
			MercWeaponItem.setAmmo(held, clip - 1, res);
		} else {
			if (res <= 0) return false;
			MercWeaponItem.setAmmo(held, 0, res - 1);
		}
		return true;
	}

	private static void startReload(ServerPlayer p, State st, WeaponDef def, ItemStack held) {
		if (def.clip() <= 0 || st.reloadAt > 0) return;
		if (MercWeaponItem.clip(held) >= def.clip() || MercWeaponItem.reserve(held) <= 0) return;
		st.reloadAt = p.level().getGameTime() + Math.max(1, def.reloadTicks());
		play(p, "reload", 0.6f);
	}

	private static void play(ServerPlayer p, WeaponDef def) {
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), MercSounds.fire(def), SoundSource.PLAYERS, 1f, 0.95f + p.getRandom().nextFloat() * 0.1f);
	}

	private static void play(ServerPlayer p, String fx, float vol) {
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), MercSounds.fx(fx), SoundSource.PLAYERS, vol, 1f);
	}

	// ------------------------------------------------------------------ fire modes

	private static void fireHitscan(ServerPlayer p, ServerLevel level, WeaponDef def, boolean crit) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		crit = crit || Combat.kritzed(p);
		for (int i = 0; i < Math.max(1, def.pellets()); i++) {
			Vec3 dir = Combat.spread(look, def.spread(), p.getRandom());
			Combat.Hit hit = Combat.raycast(p, eye, dir, def.range());
			Vec3 end = hit != null ? hit.pos() : endpoint(p, eye, dir, def.range());
			if (i % 3 == 0) tracer(level, eye.add(look.scale(0.6)).add(0, -0.1, 0), end);
			if (hit != null) {
				double dmg = def.damage() * (def.has(Trait.FALLOFF) ? Combat.falloff(hit.distance()) : 1.0);
				boolean head = def.has(Trait.HEADSHOT) && hit.pos().y > hit.entity().getEyeY() - 0.25;
				if (head) play(p, "headshot", 0.8f);
				Combat.directHit(p, hit.entity(), def, dmg, crit || head, null);
			} else {
				level.sendParticles(ParticleTypes.SMOKE, end.x, end.y, end.z, 1, 0, 0, 0, 0);
			}
		}
		if (def.id().equals("force_a_nature") && !p.onGround()) {
			p.push(look.x * -0.6, look.y * -0.6 + 0.1, look.z * -0.6);
			p.hurtMarked = true;
		}
		play(p, def);
	}

	private static void fireSniper(ServerPlayer p, ServerLevel level, WeaponDef def, boolean scoped, float charge) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = scoped ? p.getLookAngle() : Combat.spread(p.getLookAngle(), 1.0f, p.getRandom());
		Combat.Hit hit = Combat.raycast(p, eye, look, def.range());
		Vec3 end = hit != null ? hit.pos() : endpoint(p, eye, look, def.range());
		tracer(level, eye.add(look.scale(0.6)), end);
		if (hit != null) {
			double dmg = def.damage() + (scoped ? 100 * charge : 0);
			boolean head = scoped && hit.pos().y > hit.entity().getEyeY() - 0.25;
			if (head) play(p, "headshot", 1f);
			Combat.directHit(p, hit.entity(), def, dmg, head || Combat.kritzed(p), null);
		}
		play(p, def);
	}

	private static Vec3 endpoint(Player p, Vec3 eye, Vec3 dir, double range) {
		BlockHitResult b = p.level().clip(new ClipContext(eye, eye.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		return b.getLocation();
	}

	private static void tracer(ServerLevel level, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		int n = (int) Math.min(24, d.length() / 1.5);
		for (int i = 1; i <= n; i++) {
			Vec3 q = from.add(d.scale(i / (double) n));
			level.sendParticles(ParticleTypes.CRIT, q.x, q.y, q.z, 1, 0, 0, 0, 0);
		}
	}

	private static void launch(ServerPlayer p, ServerLevel level, WeaponDef def, MercProjectile.Type type, float inaccuracy) {
		MercProjectile proj = MercProjectile.create(level, p, type, def, def.damage(), Combat.kritzed(p));
		float pitch = type == MercProjectile.Type.PIPE ? p.getXRot() - 4f : p.getXRot();
		proj.shootFromRotation(p, pitch, p.getYRot(), 0, def.projectileSpeed(), inaccuracy);
		level.addFreshEntity(proj);
		play(p, def);
	}

	private static void fireFlames(ServerPlayer p, ServerLevel level, WeaponDef def) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		for (int i = 0; i < 3; i++) {
			Vec3 d = Combat.spread(look, 8f, p.getRandom()).scale(0.5 + p.getRandom().nextDouble() * 0.4);
			level.sendParticles(ParticleTypes.FLAME, eye.x + look.x * 0.8, eye.y - 0.2 + look.y * 0.8, eye.z + look.z * 0.8, 0, d.x, d.y, d.z, 1);
		}
		if (level.getGameTime() % 16 == 0) play(p, "flame_loop", 0.6f);
		double range = def.range();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.2))) {
			if (e == p) continue;
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double dist = to.length();
			if (dist > range || to.normalize().dot(look) < 0.85) continue;
			if (level.clip(new ClipContext(eye, e.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
			double dmg = def.damage() * (1.0 - 0.5 * dist / range);
			boolean crit = def.has(Trait.CRIT_FROM_BEHIND) && Combat.isBehind(p, e);
			Combat.directHit(p, e, def, dmg, crit, null);
		}
	}

	private static void airblast(ServerPlayer p, ServerLevel level) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		play(p, "airblast", 1f);
		level.sendParticles(ParticleTypes.CLOUD, eye.x + look.x * 1.5, eye.y + look.y * 1.5, eye.z + look.z * 1.5, 12, 0.4, 0.4, 0.4, 0.05);
		AABB box = new AABB(eye, eye).expandTowards(look.scale(4.5)).inflate(1.8);
		for (Entity e : level.getEntities(p, box)) {
			Vec3 to = e.position().subtract(eye);
			if (to.length() > 5 || to.normalize().dot(look) < 0.5) continue;
			if (e instanceof MercProjectile mp && !Combat.sameTeam(p, mp.getOwner())) {
				mp.reflect(p);
			} else if (e instanceof LivingEntity le && Combat.canHurt(p, le)) {
				le.push(look.x * 1.3, 0.35 + look.y * 0.5, look.z * 1.3);
				le.hurtMarked = true;
			} else if (e instanceof LivingEntity le && Combat.sameTeam(p, le)) {
				le.clearFire();
			}
		}
	}

	private static void tickMedigun(ServerPlayer p, ServerLevel level, State st, WeaponDef def, long now, boolean pressedS) {
		MercData.Status s = MercData.status(p);
		if (pressedS && s.uber() >= 1f) {
			boolean kritz = def.has(Trait.KRITZ);
			MercData.setStatus(p, s.withUber(0f).withUberTicks(160, kritz));
			LivingEntity t = healTarget(p, level, st);
			if (t != null) MercData.setStatus(t, MercData.status(t).withUberTicks(160, kritz));
			play(p, "uber", 1f);
			return;
		}
		if (!st.primary) {
			st.healTarget = null;
			return;
		}
		LivingEntity target = healTarget(p, level, st);
		if (target == null) {
			Vec3 eye = p.getEyePosition();
			Combat.Hit hit = Combat.raycast(p, eye, p.getLookAngle(), def.range());
			if (hit != null && healable(p, hit.entity())) {
				target = hit.entity();
				st.healTarget = target.getUUID();
			}
		}
		if (target == null) return;
		// beam particles
		Vec3 a = p.getEyePosition().add(0, -0.3, 0), b = target.getBoundingBox().getCenter();
		for (int i = 1; i < 8; i++) {
			Vec3 q = a.add(b.subtract(a).scale(i / 8.0));
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, q.x, q.y, q.z, 1, 0, 0, 0, 0);
		}
		if (now % 20 == 0) play(p, "medigun_heal", 0.4f);
		float max = target.getMaxHealth();
		float heal = MercConfig.dmg(24) / 20f * (target.getHealth() < max * 0.5f ? 2f : 1f);
		if (target.getHealth() < max) {
			target.heal(heal);
		} else if (target.getAbsorptionAmount() < max * 0.5f) {
			target.setAbsorptionAmount(Math.min(max * 0.5f, target.getAbsorptionAmount() + heal * 0.5f));
		}
		if (s.uberTicks() <= 0) {
			float rate = 1f / (20 * 40) * (def.has(Trait.KRITZ) ? 1.25f : 1f);
			MercData.setStatus(p, s.withUber(Math.min(1f, s.uber() + rate)));
		} else if (MercData.status(target).uberTicks() < s.uberTicks()) {
			MercData.setStatus(target, MercData.status(target).withUberTicks(s.uberTicks(), s.kritz()));
		}
	}

	private static LivingEntity healTarget(ServerPlayer p, ServerLevel level, State st) {
		if (st.healTarget == null) return null;
		Entity e = level.getEntity(st.healTarget);
		if (e instanceof LivingEntity le && le.isAlive() && le.distanceTo(p) < 13 && p.hasLineOfSight(le)) return le;
		st.healTarget = null;
		return null;
	}

	private static boolean healable(Player medic, LivingEntity e) {
		if (e instanceof Player) return Combat.sameTeam(medic, e) || Combat.teamOf(e) == null && !MercConfig.get().hurtVanillaPlayers;
		return !(e instanceof Enemy) && !(e instanceof BuildingEntity b && b.team() != Combat.teamOf(medic));
	}

	private static List<MercProjectile> stickies(Player p, ServerLevel level) {
		return level.getEntitiesOfClass(MercProjectile.class, p.getBoundingBox().inflate(96),
				m -> m.kind() == MercProjectile.Type.STICKY && m.getOwner() == p);
	}

	private static void detonateStickies(ServerPlayer p, ServerLevel level, WeaponDef def) {
		boolean selective = def.has(Trait.SELECTIVE_DETONATE);
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		boolean any = false;
		for (MercProjectile m : stickies(p, level)) {
			if (m.age() < 16) continue; // arming time
			if (selective) {
				Vec3 to = m.position().subtract(eye);
				if (to.normalize().dot(look) < 0.97) continue;
			}
			m.explode(null);
			any = true;
		}
		if (any) play(p, "sticky_detonate", 0.8f);
	}

	private static void build(ServerPlayer p, ServerLevel level, BuildingEntity.Kind kind, int cost) {
		MercData.Status s = MercData.status(p);
		if (s.metal() < cost) {
			p.sendOverlayMessage(Component.literal("Not enough metal (" + s.metal() + "/" + cost + ")"));
			return;
		}
		for (BuildingEntity b : level.getEntitiesOfClass(BuildingEntity.class, p.getBoundingBox().inflate(256))) {
			if (p.getUUID().equals(b.ownerId()) && b.kind() == kind) {
				p.sendOverlayMessage(Component.literal("You already have a " + kind.name().toLowerCase() + ". Destroy it first (sneak + left-click with PDA)."));
				return;
			}
		}
		Vec3 eye = p.getEyePosition();
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(4)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != net.minecraft.core.Direction.UP) {
			p.sendOverlayMessage(Component.literal("Look at the top of a block within 4 blocks to build."));
			return;
		}
		BlockPos pos = hit.getBlockPos().above();
		if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
			p.sendOverlayMessage(Component.literal("Not enough room here."));
			return;
		}
		BuildingEntity b = MercEntities.BUILDING.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (b == null) return;
		b.setup(kind, p);
		b.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p.getYRot() + 180f, 0f);
		level.addFreshEntity(b);
		MercData.setStatus(p, s.withMetal(s.metal() - cost));
		play(p, "build", 1f);
	}

	/** Sneak + left-click with the PDA destroys your buildings. */
	public static void demolish(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		for (BuildingEntity b : level.getEntitiesOfClass(BuildingEntity.class, p.getBoundingBox().inflate(256))) {
			if (p.getUUID().equals(b.ownerId())) b.discard();
		}
	}

	private static void sap(ServerPlayer p, ServerLevel level, WeaponDef def) {
		Combat.Hit hit = Combat.raycast(p, p.getEyePosition(), p.getLookAngle(), def.range());
		if (hit == null) return;
		if (hit.entity() instanceof BuildingEntity b && b.team() != Combat.teamOf(p)) {
			b.sap(20 * 30);
			play(p, "build", 0.8f);
		} else if (hit.entity() instanceof Mob m && Combat.canHurt(p, m)) {
			m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 5));
			m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 2));
		}
	}

	// ------------------------------------------------------------------ cloak

	public static void toggleCloak(ServerPlayer p) {
		MercData.Loadout l = MercData.loadout(p);
		if (l.mercClass() != MercClass.SPY) return;
		MercData.Status s = MercData.status(p);
		if (s.cloaked()) {
			MercData.setStatus(p, s.withCloak(s.cloak(), false));
			uncloak(p);
		} else if (s.cloak() > 0.1f) {
			MercData.setStatus(p, s.withCloak(s.cloak(), true));
			p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10, 0, false, false, false));
			play(p, "cloak", 0.8f);
		}
	}

	private static void uncloak(ServerPlayer p) {
		p.removeEffect(MobEffects.INVISIBILITY);
		play(p, "uncloak", 0.8f);
	}
}
