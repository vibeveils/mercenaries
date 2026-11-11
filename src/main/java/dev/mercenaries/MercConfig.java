package dev.mercenaries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side tuning, stored in {@code config/mercenaries.json}.
 */
public final class MercConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static MercConfig instance = new MercConfig();

	/** TF2 hit points per Minecraft health point. 6.25 maps a 125 HP Scout to 20 (10 hearts). */
	public double hpScale = 6.25;
	/** Whether explosives break blocks. Off by default so builds survive firefights. */
	public boolean explosionsBreakBlocks = false;
	/** Whether merc weapons can hurt players with no merc team (vanilla players). */
	public boolean hurtVanillaPlayers = true;
	/** Percent of reserve ammo regenerated every 5 seconds while not firing (keeps survival playable). */
	public double passiveAmmoRegenPercent = 5.0;
	/** Engineer metal regenerated every 5 seconds. */
	public int passiveMetalRegen = 10;
	/** Open the class picker automatically the first time a player joins. */
	public boolean promptOnFirstJoin = true;

	public double hpScale() {
		return hpScale <= 0 ? 6.25 : hpScale;
	}

	public static MercConfig get() {
		return instance;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mercenaries.json");
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					MercConfig c = GSON.fromJson(r, MercConfig.class);
					if (c != null) instance = c;
				}
			}
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException | RuntimeException e) {
			Mercenaries.LOGGER.warn("Could not read/write {}: {}", file, e.toString());
		}
	}

	/** Converts a TF2 damage number into Minecraft health points. */
	public static float dmg(double tf2) {
		return (float) (tf2 / get().hpScale());
	}
}
