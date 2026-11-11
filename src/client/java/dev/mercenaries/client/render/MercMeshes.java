package dev.mercenaries.client.render;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import dev.mercenaries.Mercenaries;

/**
 * Loads mesh JSON written by tools/extract_tf2_assets.py from
 * {@code assets/mercenaries/merc_models/**.json}. If the user hasn't generated/enabled the pack,
 * nothing is loaded and everything falls back to vanilla rendering.
 */
public final class MercMeshes implements ResourceManagerReloadListener {
	public static final MercMeshes INSTANCE = new MercMeshes();
	private final Map<String, MercMesh> meshes = new HashMap<>();

	private MercMeshes() {
	}

	/** @param key e.g. "player/soldier", "weapon/rocket_launcher", "prop/sentry" */
	public MercMesh get(String key) {
		return meshes.get(key);
	}

	public boolean hasAny() {
		return !meshes.isEmpty();
	}

	@Override
	public void onResourceManagerReload(ResourceManager manager) {
		meshes.clear();
		Map<Identifier, Resource> found = manager.listResources("merc_models",
				id -> id.getNamespace().equals(Mercenaries.MOD_ID) && id.getPath().endsWith(".json") && !id.getPath().endsWith("manifest.json"));
		for (Map.Entry<Identifier, Resource> e : found.entrySet()) {
			String path = e.getKey().getPath(); // merc_models/player/scout.json
			String key = path.substring("merc_models/".length(), path.length() - ".json".length());
			try (Reader r = e.getValue().openAsReader()) {
				meshes.put(key, parse(JsonParser.parseReader(r).getAsJsonObject()));
			} catch (Exception ex) {
				Mercenaries.LOGGER.warn("Failed to load mesh {}: {}", e.getKey(), ex.toString());
			}
		}
		if (!meshes.isEmpty()) Mercenaries.LOGGER.info("Loaded {} TF2 meshes from resource packs", meshes.size());
	}

	private static MercMesh parse(JsonObject o) {
		List<List<Identifier>> skins = new ArrayList<>();
		for (JsonElement fam : o.getAsJsonArray("skins")) {
			List<Identifier> row = new ArrayList<>();
			for (JsonElement t : fam.getAsJsonArray()) row.add(t.isJsonNull() ? null : Identifier.parse(t.getAsString()));
			skins.add(row);
		}
		List<String> modes = new ArrayList<>();
		if (o.has("modes")) for (JsonElement m : o.getAsJsonArray("modes")) modes.add(m.getAsString());
		Map<String, MercMesh.Part> parts = new HashMap<>();
		for (Map.Entry<String, JsonElement> pe : o.getAsJsonObject("parts").entrySet()) {
			JsonObject po = pe.getValue().getAsJsonObject();
			float[] pivot = floats(po.getAsJsonArray("pivot"));
			List<MercMesh.Sub> subs = new ArrayList<>();
			for (JsonElement me : po.getAsJsonArray("meshes")) {
				JsonObject mo = me.getAsJsonObject();
				JsonArray idx = mo.getAsJsonArray("i");
				int[] ind = new int[idx.size()];
				for (int i = 0; i < ind.length; i++) ind[i] = idx.get(i).getAsInt();
				subs.add(new MercMesh.Sub(mo.get("mat").getAsInt(), floats(mo.getAsJsonArray("v")), floats(mo.getAsJsonArray("n")),
						floats(mo.getAsJsonArray("uv")), ind));
			}
			parts.put(pe.getKey(), new MercMesh.Part(pivot, subs));
		}
		return new MercMesh(skins, modes, parts);
	}

	private static float[] floats(JsonArray a) {
		float[] f = new float[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
		return f;
	}
}
