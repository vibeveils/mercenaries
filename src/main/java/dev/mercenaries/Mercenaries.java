package dev.mercenaries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

import dev.mercenaries.data.MercData;
import dev.mercenaries.entity.MercEntities;
import dev.mercenaries.net.MercPayloads;
import dev.mercenaries.weapon.MercComponents;
import dev.mercenaries.weapon.Weapons;

public final class Mercenaries implements ModInitializer {
	public static final String MOD_ID = "mercenaries";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		MercConfig.load();
		MercData.init();
		MercComponents.init();
		Weapons.register();
		MercEntities.register();
		MercSounds.register();
		MercPayloads.register();
		MercServer.init();
		LOGGER.info("Mercenaries loaded. Assets come from your own TF2 install via tools/extract_tf2_assets.py.");
	}
}
