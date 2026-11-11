package dev.mercenaries.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import dev.mercenaries.Mercenaries;

public final class MercPayloads {
	private MercPayloads() {
	}

	/** Client -> server: pick team, class and loadout. cls = 0 returns to vanilla. */
	public record Select(int team, int cls, String primary, String secondary, String melee) implements CustomPacketPayload {
		public static final Type<Select> TYPE = new Type<>(Mercenaries.id("select"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Select::team,
				ByteBufCodecs.VAR_INT, Select::cls,
				ByteBufCodecs.STRING_UTF8, Select::primary,
				ByteBufCodecs.STRING_UTF8, Select::secondary,
				ByteBufCodecs.STRING_UTF8, Select::melee,
				Select::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: mouse buttons while holding a ranged merc weapon. bit0 primary, bit1 secondary. */
	public record Input(int buttons) implements CustomPacketPayload {
		public static final Type<Input> TYPE = new Type<>(Mercenaries.id("input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Input::buttons, Input::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: one-shot actions. */
	public record Action(int action) implements CustomPacketPayload {
		public static final int RELOAD = 0, ABILITY = 1, DEMOLISH = 2;
		public static final Type<Action> TYPE = new Type<>(Mercenaries.id("action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Action::action, Action::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: open the class picker. */
	public record OpenPicker(boolean firstTime) implements CustomPacketPayload {
		public static final Type<OpenPicker> TYPE = new Type<>(Mercenaries.id("open_picker"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenPicker> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, OpenPicker::firstTime, OpenPicker::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Select.TYPE, Select.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Input.TYPE, Input.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenPicker.TYPE, OpenPicker.CODEC);
	}
}
