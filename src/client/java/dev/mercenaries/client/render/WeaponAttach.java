package dev.mercenaries.client.render;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;

/**
 * The third-person version of a TF2 weapon model: its own skeleton (which shares bone names such as
 * "weapon_bone" with the class skeletons) plus skinned meshes. Drawn bone-merged onto the player.
 */
public final class WeaponAttach {
	public final String[] boneNames;
	public final int[] parents;
	public final float[][] p2b, bindWorld, relBind;
	public final List<SkinnedMesh.Sub> subs;
	private final List<List<Identifier>> skins;
	private final List<String> modes;
	private final Map<SkinnedMesh, int[]> remapCache = new WeakHashMap<>();

	WeaponAttach(List<List<Identifier>> skins, List<String> modes, JsonObject o) {
		this.skins = skins;
		this.modes = modes;
		JsonArray bones = o.getAsJsonArray("bones");
		int n = bones.size();
		boneNames = new String[n];
		parents = new int[n];
		p2b = new float[n][];
		bindWorld = new float[n][];
		relBind = new float[n][];
		for (int b = 0; b < n; b++) {
			JsonObject bo = bones.get(b).getAsJsonObject();
			boneNames[b] = bo.get("name").getAsString().toLowerCase();
			parents[b] = bo.get("parent").getAsInt();
			p2b[b] = SkinnedMesh.from3x4(SkinnedMesh.floats(bo.getAsJsonArray("p2b")));
			bindWorld[b] = SkinnedMesh.invertAffine(p2b[b]);
		}
		for (int b = 0; b < n; b++) {
			relBind[b] = parents[b] >= 0 ? SkinnedMesh.mul(SkinnedMesh.invertAffine(bindWorld[parents[b]]), bindWorld[b]) : bindWorld[b];
		}
		subs = SkinnedMesh.parseSubs(o.getAsJsonArray("meshes"));
	}

	/** For each weapon bone, the index of the same-named bone in {@code body}, or -1. */
	public int[] remap(SkinnedMesh body) {
		return remapCache.computeIfAbsent(body, m -> {
			int[] r = new int[boneNames.length];
			for (int b = 0; b < r.length; b++) r[b] = m.boneIndex(boneNames[b]);
			return r;
		});
	}

	public Identifier texture(int skin, int mat) {
		if (skins.isEmpty()) return null;
		List<Identifier> fam = skins.get(Math.min(Math.max(0, skin), skins.size() - 1));
		Identifier t = mat < fam.size() ? fam.get(mat) : null;
		if (t == null && mat < skins.getFirst().size()) t = skins.getFirst().get(mat);
		return t;
	}

	public String mode(int mat) {
		return mat < modes.size() ? modes.get(mat) : "cutout";
	}
}
