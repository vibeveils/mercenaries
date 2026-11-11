package dev.mercenaries.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import dev.mercenaries.MercClass;
import dev.mercenaries.MercTeam;
import dev.mercenaries.Mercenaries;

/**
 * Player data stored with Fabric's Data Attachment API.
 *
 * <ul>
 *   <li>{@link Loadout}: chosen team, class and weapons. Persistent, survives death, synced to everyone
 *   (other clients need it to render the right model).</li>
 *   <li>{@link Status}: Über meter, metal, cloak etc. Synced to everyone so the HUD and cloak rendering work.</li>
 * </ul>
 */
public final class MercData {
	private MercData() {
	}

	public record Loadout(int team, int cls, String primary, String secondary, String melee) {
		public static final Loadout NONE = new Loadout(0, 0, "", "", "");

		public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.optionalFieldOf("team", 0).forGetter(Loadout::team),
				Codec.INT.optionalFieldOf("class", 0).forGetter(Loadout::cls),
				Codec.STRING.optionalFieldOf("primary", "").forGetter(Loadout::primary),
				Codec.STRING.optionalFieldOf("secondary", "").forGetter(Loadout::secondary),
				Codec.STRING.optionalFieldOf("melee", "").forGetter(Loadout::melee)
		).apply(i, Loadout::new));

		public static final StreamCodec<RegistryFriendlyByteBuf, Loadout> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Loadout::team,
				ByteBufCodecs.VAR_INT, Loadout::cls,
				ByteBufCodecs.STRING_UTF8, Loadout::primary,
				ByteBufCodecs.STRING_UTF8, Loadout::secondary,
				ByteBufCodecs.STRING_UTF8, Loadout::melee,
				Loadout::new);

		public MercClass mercClass() {
			return MercClass.byOrdinal(cls);
		}

		public MercTeam mercTeam() {
			return MercTeam.byOrdinal(team);
		}

		public boolean active() {
			return mercClass() != MercClass.NONE;
		}

		public String slot(int i) {
			return switch (i) {
				case 0 -> primary;
				case 1 -> secondary;
				default -> melee;
			};
		}
	}

	/**
	 * @param uber     0..1 ÜberCharge meter (Medic)
	 * @param uberTicks remaining ticks of active Über (>0 = invulnerable / crit-boosted)
	 * @param kritz    true if the active Über is a Kritzkrieg crit boost
	 * @param metal    Engineer metal 0..200
	 * @param cloak    0..1 cloak energy (Spy)
	 * @param cloaked  currently cloaked
	 * @param charge   0..1 generic charge (sniper rifle, huntsman, minigun spin)
	 * @param stickies live stickybombs
	 * @param cooldown 0..1 consumable recharge (Sandvich, Bonk!, Jarate)
	 */
	public record Status(float uber, int uberTicks, boolean kritz, int metal, float cloak, boolean cloaked,
						 float charge, int stickies, float cooldown) {
		public static final Status DEFAULT = new Status(0f, 0, false, 200, 1f, false, 0f, 0, 1f);

		public static final Codec<Status> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("uber", 0f).forGetter(Status::uber),
				Codec.INT.optionalFieldOf("uber_ticks", 0).forGetter(Status::uberTicks),
				Codec.BOOL.optionalFieldOf("kritz", false).forGetter(Status::kritz),
				Codec.INT.optionalFieldOf("metal", 200).forGetter(Status::metal),
				Codec.FLOAT.optionalFieldOf("cloak", 1f).forGetter(Status::cloak),
				Codec.BOOL.optionalFieldOf("cloaked", false).forGetter(Status::cloaked),
				Codec.FLOAT.optionalFieldOf("charge", 0f).forGetter(Status::charge),
				Codec.INT.optionalFieldOf("stickies", 0).forGetter(Status::stickies),
				Codec.FLOAT.optionalFieldOf("cooldown", 1f).forGetter(Status::cooldown)
		).apply(i, Status::new));

		public static final StreamCodec<RegistryFriendlyByteBuf, Status> STREAM_CODEC = StreamCodec.of(
				(buf, s) -> {
					buf.writeFloat(s.uber);
					buf.writeVarInt(s.uberTicks);
					buf.writeBoolean(s.kritz);
					buf.writeVarInt(s.metal);
					buf.writeFloat(s.cloak);
					buf.writeBoolean(s.cloaked);
					buf.writeFloat(s.charge);
					buf.writeVarInt(s.stickies);
					buf.writeFloat(s.cooldown);
				},
				buf -> new Status(buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readFloat(),
						buf.readBoolean(), buf.readFloat(), buf.readVarInt(), buf.readFloat()));

		public Status withUber(float v) { return new Status(v, uberTicks, kritz, metal, cloak, cloaked, charge, stickies, cooldown); }
		public Status withUberTicks(int t, boolean k) { return new Status(uber, t, k, metal, cloak, cloaked, charge, stickies, cooldown); }
		public Status withMetal(int v) { return new Status(uber, uberTicks, kritz, Math.max(0, Math.min(200, v)), cloak, cloaked, charge, stickies, cooldown); }
		public Status withCloak(float v, boolean c) { return new Status(uber, uberTicks, kritz, metal, v, c, charge, stickies, cooldown); }
		public Status withCharge(float v) { return new Status(uber, uberTicks, kritz, metal, cloak, cloaked, v, stickies, cooldown); }
		public Status withStickies(int v) { return new Status(uber, uberTicks, kritz, metal, cloak, cloaked, charge, v, cooldown); }
		public Status withCooldown(float v) { return new Status(uber, uberTicks, kritz, metal, cloak, cloaked, charge, stickies, v); }
	}

	public static final AttachmentType<Loadout> LOADOUT = AttachmentRegistry.create(Mercenaries.id("loadout"),
			builder -> builder
					.initializer(() -> Loadout.NONE)
					.persistent(Loadout.CODEC)
					.copyOnDeath()
					.syncWith(Loadout.STREAM_CODEC, AttachmentSyncPredicate.all()));

	public static final AttachmentType<Status> STATUS = AttachmentRegistry.create(Mercenaries.id("status"),
			builder -> builder
					.initializer(() -> Status.DEFAULT)
					.persistent(Status.CODEC)
					.syncWith(Status.STREAM_CODEC, AttachmentSyncPredicate.all()));

	/** Marks that a player has been offered the class picker at least once. */
	public static final AttachmentType<Boolean> PROMPTED = AttachmentRegistry.create(Mercenaries.id("prompted"),
			builder -> builder.initializer(() -> false).persistent(Codec.BOOL).copyOnDeath());

	public static Loadout loadout(Entity e) {
		Loadout l = e.getAttached(LOADOUT);
		return l == null ? Loadout.NONE : l;
	}

	public static Status status(Entity e) {
		Status s = e.getAttached(STATUS);
		return s == null ? Status.DEFAULT : s;
	}

	public static void setStatus(Entity e, Status s) {
		if (!s.equals(e.getAttached(STATUS))) {
			e.setAttached(STATUS, s);
		}
	}

	public static void init() {
		// class-load the static fields
	}
}
