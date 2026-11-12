package dev.mercenaries.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;

/**
 * A skinned TF2 mesh with skeleton and animation clips (format 2, written by extract_tf2_assets.py).
 * All matrices are row-major float[16]. Vertices are stored in Source model space; {@link #toEntity}
 * maps them into Minecraft's entity-model space (x left, y down, z back, feet at y = 1.5).
 */
public final class SkinnedMesh {
	public final List<List<Identifier>> skins;
	public final List<String> modes;
	public final String[] boneNames;
	public final int[] parents;
	public final float[][] p2b;      // per bone 4x4 poseToBone (inverse bind)
	public final float[][] bindWorld; // per bone 4x4 bind pose world
	public final float[] toEntity;
	public final int headBone;
	public final List<Sub> subs;
	public final Map<String, Clip> clips;
	/** For each bone, whether it is the head bone or one of its descendants. */
	public final boolean[] inHead;
	/** Bind pose in local (parent-relative) pos + quat, bones*7. */
	private final float[] bindLocal;

	public record Sub(int mat, float[] v, float[] n, float[] uv, int[] bi, float[] bw, int[] i) {
	}

	/** Bones listed in {@code constant} never move; {@code anim} bones have frames*anim.length*7 values. */
	public record Clip(float fps, int frames, boolean loop, Map<Integer, float[]> constant, int[] anim, float[] data) {
	}

	SkinnedMesh(List<List<Identifier>> skins, List<String> modes, JsonObject o) {
		this.skins = skins;
		this.modes = modes;
		JsonArray bones = o.getAsJsonArray("bones");
		int n = bones.size();
		boneNames = new String[n];
		parents = new int[n];
		p2b = new float[n][];
		bindWorld = new float[n][];
		for (int b = 0; b < n; b++) {
			JsonObject bo = bones.get(b).getAsJsonObject();
			boneNames[b] = bo.get("name").getAsString().toLowerCase();
			parents[b] = bo.get("parent").getAsInt();
			p2b[b] = from3x4(floats(bo.getAsJsonArray("p2b")));
			bindWorld[b] = invertAffine(p2b[b]);
		}
		toEntity = floats(o.getAsJsonArray("to_entity"));
		headBone = o.has("head_bone") ? o.get("head_bone").getAsInt() : -1;
		inHead = new boolean[n];
		for (int b = 0; b < n; b++) {
			for (int p = b; p >= 0; p = parents[p]) {
				if (p == headBone) {
					inHead[b] = true;
					break;
				}
			}
		}
		bindLocal = new float[n * 7];
		for (int b = 0; b < n; b++) {
			float[] local = parents[b] >= 0 ? mul(invertAffine(bindWorld[parents[b]]), bindWorld[b]) : bindWorld[b];
			bindLocal[b * 7] = local[3];
			bindLocal[b * 7 + 1] = local[7];
			bindLocal[b * 7 + 2] = local[11];
			matToQuat(local, bindLocal, b * 7 + 3);
		}
		subs = parseSubs(o.getAsJsonArray("meshes"));
		clips = new HashMap<>();
		if (o.has("clips")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("clips").entrySet()) {
				JsonObject c = e.getValue().getAsJsonObject();
				Map<Integer, float[]> constant = new HashMap<>();
				for (Map.Entry<String, JsonElement> ce : c.getAsJsonObject("const").entrySet()) {
					constant.put(Integer.parseInt(ce.getKey()), floats(ce.getValue().getAsJsonArray()));
				}
				JsonArray anim = c.getAsJsonArray("anim");
				int[] ai = new int[anim.size()];
				for (int i = 0; i < ai.length; i++) ai[i] = anim.get(i).getAsInt();
				clips.put(e.getKey(), new Clip(c.get("fps").getAsFloat(), c.get("frames").getAsInt(), c.get("loop").getAsBoolean(),
						constant, ai, floats(c.getAsJsonArray("data"))));
			}
		}
	}

	public int boneIndex(String name) {
		String n = name.toLowerCase();
		for (int i = 0; i < boneNames.length; i++) if (boneNames[i].equals(n)) return i;
		return -1;
	}

	static List<Sub> parseSubs(JsonArray meshes) {
		List<Sub> out = new ArrayList<>();
		for (JsonElement me : meshes) {
			JsonObject mo = me.getAsJsonObject();
			out.add(new Sub(mo.get("mat").getAsInt(), floats(mo.getAsJsonArray("v")), floats(mo.getAsJsonArray("n")),
					floats(mo.getAsJsonArray("uv")), ints(mo.getAsJsonArray("bi")), floats(mo.getAsJsonArray("bw")), ints(mo.getAsJsonArray("i"))));
		}
		return out;
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

	// ------------------------------------------------------------------ sampling

	/** Writes local pos (3) + quat (4) for every bone into {@code out} (bones*7). */
	public void sample(Clip clip, float frame, float[] out) {
		int n = boneNames.length;
		// default: bind-local pose (from bind world matrices)
		for (int b = 0; b < n; b++) {
			float[] c = clip == null ? null : clip.constant().get(b);
			if (c != null) {
				System.arraycopy(c, 0, out, b * 7, 7);
			} else {
				System.arraycopy(bindLocal, b * 7, out, b * 7, 7);
			}
		}
		if (clip == null || clip.anim().length == 0 || clip.frames() <= 0) return;
		int frames = clip.frames();
		float f = clip.loop() ? (frame % frames + frames) % frames : Math.max(0, Math.min(frames - 1, frame));
		int f0 = (int) Math.floor(f);
		int f1 = clip.loop() ? (f0 + 1) % frames : Math.min(frames - 1, f0 + 1);
		float t = f - f0;
		int[] anim = clip.anim();
		float[] d = clip.data();
		int stride = anim.length * 7;
		for (int k = 0; k < anim.length; k++) {
			int b = anim[k];
			int a = f0 * stride + k * 7, c = f1 * stride + k * 7, o = b * 7;
			for (int j = 0; j < 3; j++) out[o + j] = d[a + j] + (d[c + j] - d[a + j]) * t;
			nlerp(d, a + 3, d, c + 3, t, out, o + 3);
		}
	}

	public static void blend(float[] a, float[] b, float t, float[] out) {
		for (int i = 0; i < a.length; i += 7) {
			for (int j = 0; j < 3; j++) out[i + j] = a[i + j] + (b[i + j] - a[i + j]) * t;
			nlerp(a, i + 3, b, i + 3, t, out, i + 3);
		}
	}

	/** Bone world matrices in entity space: toEntity * hierarchy(local). */
	public void worldMatrices(float[] local, float[][] out) {
		for (int b = 0; b < parents.length; b++) {
			float[] m = trs(local, b * 7);
			out[b] = parents[b] >= 0 && parents[b] < b ? mul(out[parents[b]], m) : mul(toEntity, m);
		}
	}

	/** Rotates the head bone and its children around the head joint (entity space). */
	public void applyHeadLook(float[][] world, float yRot, float xRot) {
		if (headBone < 0 || headBone >= world.length || (yRot == 0 && xRot == 0)) return;
		float px = world[headBone][3], py = world[headBone][7], pz = world[headBone][11];
		float[] r = mul(rotY(yRot), rotX(xRot));
		float[] pivot = mul(translate(px, py, pz), mul(r, translate(-px, -py, -pz)));
		for (int b = 0; b < world.length; b++) if (inHead[b]) world[b] = mul(pivot, world[b]);
	}

	// ------------------------------------------------------------------ matrix helpers (row-major 4x4)

	static float[] from3x4(float[] m) {
		return new float[]{m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], 0, 0, 0, 1};
	}

	public static float[] mul(float[] a, float[] b) {
		float[] r = new float[16];
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + a[i * 4 + 3] * b[12 + j];
			}
		}
		return r;
	}

	public static float[] invertAffine(float[] m) {
		// inverse of [R|t] with R possibly scaled: use general 3x3 inverse
		float a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
		float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
		float det = a * A + b * B + c * C;
		if (Math.abs(det) < 1e-12f) det = 1e-12f;
		float inv = 1f / det;
		float[] r = new float[16];
		r[0] = A * inv; r[1] = -(b * i - c * h) * inv; r[2] = (b * f - c * e) * inv;
		r[4] = B * inv; r[5] = (a * i - c * g) * inv; r[6] = -(a * f - c * d) * inv;
		r[8] = C * inv; r[9] = -(a * h - b * g) * inv; r[10] = (a * e - b * d) * inv;
		r[3] = -(r[0] * m[3] + r[1] * m[7] + r[2] * m[11]);
		r[7] = -(r[4] * m[3] + r[5] * m[7] + r[6] * m[11]);
		r[11] = -(r[8] * m[3] + r[9] * m[7] + r[10] * m[11]);
		r[15] = 1;
		return r;
	}

	static float[] trs(float[] l, int o) {
		float x = l[o + 3], y = l[o + 4], z = l[o + 5], w = l[o + 6];
		return new float[]{
				1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), l[o],
				2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), l[o + 1],
				2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), l[o + 2],
				0, 0, 0, 1};
	}

	static void matToQuat(float[] m, float[] out, int o) {
		// normalise rows to strip scale
		float sx = (float) Math.sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8]);
		float sy = (float) Math.sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9]);
		float sz = (float) Math.sqrt(m[2] * m[2] + m[6] * m[6] + m[10] * m[10]);
		float r00 = m[0] / sx, r11 = m[5] / sy, r22 = m[10] / sz;
		float r01 = m[1] / sy, r02 = m[2] / sz, r10 = m[4] / sx, r12 = m[6] / sz, r20 = m[8] / sx, r21 = m[9] / sy;
		float tr = r00 + r11 + r22, x, y, z, w;
		if (tr > 0) {
			float s = (float) Math.sqrt(tr + 1) * 2;
			w = 0.25f * s; x = (r21 - r12) / s; y = (r02 - r20) / s; z = (r10 - r01) / s;
		} else if (r00 > r11 && r00 > r22) {
			float s = (float) Math.sqrt(1 + r00 - r11 - r22) * 2;
			w = (r21 - r12) / s; x = 0.25f * s; y = (r01 + r10) / s; z = (r02 + r20) / s;
		} else if (r11 > r22) {
			float s = (float) Math.sqrt(1 + r11 - r00 - r22) * 2;
			w = (r02 - r20) / s; x = (r01 + r10) / s; y = 0.25f * s; z = (r12 + r21) / s;
		} else {
			float s = (float) Math.sqrt(1 + r22 - r00 - r11) * 2;
			w = (r10 - r01) / s; x = (r02 + r20) / s; y = (r12 + r21) / s; z = 0.25f * s;
		}
		out[o] = x; out[o + 1] = y; out[o + 2] = z; out[o + 3] = w;
	}

	static void nlerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int o) {
		float dot = a[ao] * b[bo] + a[ao + 1] * b[bo + 1] + a[ao + 2] * b[bo + 2] + a[ao + 3] * b[bo + 3];
		float s = dot < 0 ? -1 : 1;
		float x = a[ao] + (s * b[bo] - a[ao]) * t, y = a[ao + 1] + (s * b[bo + 1] - a[ao + 1]) * t;
		float z = a[ao + 2] + (s * b[bo + 2] - a[ao + 2]) * t, w = a[ao + 3] + (s * b[bo + 3] - a[ao + 3]) * t;
		float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		if (len < 1e-8f) len = 1;
		out[o] = x / len; out[o + 1] = y / len; out[o + 2] = z / len; out[o + 3] = w / len;
	}

	static float[] translate(float x, float y, float z) {
		return new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
	}

	static float[] rotX(float a) {
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		return new float[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
	}

	static float[] rotY(float a) {
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		return new float[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
	}

	// ------------------------------------------------------------------ json helpers

	static float[] floats(JsonArray a) {
		float[] f = new float[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
		return f;
	}

	static int[] ints(JsonArray a) {
		if (a == null) return new int[0];
		int[] f = new int[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsInt();
		return f;
	}
}
