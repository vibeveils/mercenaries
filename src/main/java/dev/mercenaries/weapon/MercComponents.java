package dev.mercenaries.weapon;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;

import dev.mercenaries.Mercenaries;

/** Item data components used by mercenary weapons. */
public final class MercComponents {
	private MercComponents() {
	}

	/** Rounds currently loaded. */
	public static final DataComponentType<Integer> CLIP = register("clip");
	/** Rounds in reserve. */
	public static final DataComponentType<Integer> RESERVE = register("reserve");

	private static DataComponentType<Integer> register(String name) {
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Mercenaries.id(name),
				DataComponentType.<Integer>builder().persistent(ExtraCodecs.NON_NEGATIVE_INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	}

	public static void init() {
	}
}
