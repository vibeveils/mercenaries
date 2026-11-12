package dev.mercenaries.client.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Per-entity animation state: picks a TF2 clip from movement state, advances its phase, crossfades on
 * changes, and CPU-skins the class mesh (plus the held weapon, bone-merged onto the hand) each frame.
 */
public final class Animator {
	private static final Map<Integer, Animator> BY_ENTITY = new HashMap<>();
	private static final float BLEND_SECONDS = 0.2f;

	private SkinnedMesh mesh;
	private String clipKey, prevKey;
	private float phase, prevPhase, blendStart = -1, lastTime = -1;
	private float[] local, localPrev, localMix;
	private float[][] world;
	private final Map<SkinnedMesh.Sub, float[][]> skinned = new HashMap<>();
	private final Map<Object, float[][]> weaponWorldCache = new HashMap<>();
	private long lastUse;

	public static Animator get(int entityId, SkinnedMesh mesh) {
		Animator a = BY_ENTITY.computeIfAbsent(entityId, k -> new Animator());
		if (a.mesh != mesh) a.reset(mesh);
		a.lastUse = System.nanoTime();
		if (BY_ENTITY.size() > 256) BY_ENTITY.values().removeIf(x -> System.nanoTime() - x.lastUse > 30_000_000_000L);
		return a;
	}

	public static void clearAll() {
		BY_ENTITY.clear();
	}

	private void reset(SkinnedMesh m) {
		mesh = m;
		int n = m.boneNames.length;
		local = new float[n * 7];
		localPrev = new float[n * 7];
		localMix = new float[n * 7];
		world = new float[n][];
		skinned.clear();
		weaponWorldCache.clear();
		clipKey = prevKey = null;
		phase = prevPhase = 0;
		lastTime = -1;
	}

	/** Movement state -> clip key (falls back through slots and states). */
	public static String chooseClip(SkinnedMesh m, String state, String slot) {
		String[] slots = {slot, "primary", "secondary", "melee"};
		String[] states = {state, state.startsWith("crouch") ? "crouch" : "stand", "stand"};
		for (String st : states) for (String sl : slots) if (m.clips.containsKey(st + "_" + sl)) return st + "_" + sl;
		return m.clips.isEmpty() ? null : m.clips.keySet().iterator().next();
	}

	/**
	 * @param time  seconds (entity age + partial tick)
	 * @param rate  playback multiplier (movement speed relative to class run speed)
	 */
	public void update(String key, float time, float rate, float headYaw, float headPitch) {
		float dt = lastTime < 0 ? 0 : Math.max(0, Math.min(0.25f, time - lastTime));
		lastTime = time;
		if (key != null && !key.equals(clipKey)) {
			prevKey = clipKey;
			prevPhase = phase;
			clipKey = key;
			phase = 0;
			blendStart = time;
		}
		SkinnedMesh.Clip clip = clipKey == null ? null : mesh.clips.get(clipKey);
		SkinnedMesh.Clip prev = prevKey == null ? null : mesh.clips.get(prevKey);
		if (clip != null) phase += dt * clip.fps() * rate;
		if (prev != null) prevPhase += dt * prev.fps() * rate;

		mesh.sample(clip, phase, local);
		float blend = blendStart < 0 ? 1 : (time - blendStart) / BLEND_SECONDS;
		if (prev != null && blend < 1) {
			mesh.sample(prev, prevPhase, localPrev);
			SkinnedMesh.blend(localPrev, local, Math.max(0, blend), localMix);
			mesh.worldMatrices(localMix, world);
		} else {
			mesh.worldMatrices(local, world);
		}
		mesh.applyHeadLook(world, headYaw, headPitch);
		weaponWorldCache.clear();
	}

	public float[][] world() {
		return world;
	}

	// ------------------------------------------------------------------ submission

	public void submitBody(PoseStack ps, SubmitNodeCollector c, int skin, int light, int overlay, int color) {
		float[][] skinM = new float[world.length][];
		for (int b = 0; b < world.length; b++) skinM[b] = SkinnedMesh.mul(world[b], mesh.p2b[b]);
		submitSubs(mesh.subs, skinM, ps, c, skin, light, overlay, color, mesh::texture, mesh::mode);
	}

	/** Draws a weapon's "attach" mesh, bone-merged onto this skeleton by bone name (TF2 style). */
	public void submitWeapon(WeaponAttach w, PoseStack ps, SubmitNodeCollector c, int skin, int light, int overlay) {
		int n = w.boneNames.length;
		float[][] ww = new float[n][];
		for (int b = 0; b < n; b++) {
			int pi = w.remap(mesh)[b];
			if (pi >= 0) {
				ww[b] = world[pi];
			} else if (w.parents[b] >= 0 && w.parents[b] < b) {
				ww[b] = SkinnedMesh.mul(ww[w.parents[b]], w.relBind[b]);
			} else {
				ww[b] = SkinnedMesh.mul(mesh.toEntity, w.bindWorld[b]);
			}
		}
		float[][] skinM = new float[n][];
		for (int b = 0; b < n; b++) skinM[b] = SkinnedMesh.mul(ww[b], w.p2b[b]);
		submitSubs(w.subs, skinM, ps, c, skin, light, overlay, 0xFFFFFFFF, w::texture, w::mode);
	}

	interface TexFn { Identifier get(int skin, int mat); }
	interface ModeFn { String get(int mat); }

	private void submitSubs(List<SkinnedMesh.Sub> subs, float[][] skinM, PoseStack ps, SubmitNodeCollector c, int skin,
							int light, int overlay, int color, TexFn tex, ModeFn mode) {
		for (SkinnedMesh.Sub sub : subs) {
			Identifier t = tex.get(skin, sub.mat());
			if (t == null) continue;
			float[][] buf = skinned.computeIfAbsent(sub, s -> new float[][]{new float[s.v().length], new float[s.n().length]});
			skin(sub, skinM, buf[0], buf[1]);
			RenderType rt = "translucent".equals(mode.get(sub.mat())) ? RenderTypes.entityTranslucent(t) : RenderTypes.armorCutoutNoCull(t);
			float[] pos = buf[0].clone(), nrm = buf[1].clone(); // the draw happens later; snapshot this frame
			c.submitCustomGeometry(ps, rt, (pose, vc) -> emit(sub, pos, nrm, pose, vc, light, overlay, color));
		}
	}

	private static void skin(SkinnedMesh.Sub s, float[][] m, float[] outP, float[] outN) {
		float[] v = s.v(), n = s.n(), w = s.bw();
		int[] bi = s.bi();
		int count = v.length / 3;
		for (int i = 0; i < count; i++) {
			float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
			float nx = n[i * 3], ny = n[i * 3 + 1], nz = n[i * 3 + 2];
			float px = 0, py = 0, pz = 0, qx = 0, qy = 0, qz = 0;
			for (int k = 0; k < 3; k++) {
				float wt = w[i * 3 + k];
				if (wt <= 0) continue;
				int b = bi[i * 3 + k];
				if (b < 0 || b >= m.length) continue;
				float[] M = m[b];
				px += wt * (M[0] * x + M[1] * y + M[2] * z + M[3]);
				py += wt * (M[4] * x + M[5] * y + M[6] * z + M[7]);
				pz += wt * (M[8] * x + M[9] * y + M[10] * z + M[11]);
				qx += wt * (M[0] * nx + M[1] * ny + M[2] * nz);
				qy += wt * (M[4] * nx + M[5] * ny + M[6] * nz);
				qz += wt * (M[8] * nx + M[9] * ny + M[10] * nz);
			}
			float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
			if (len < 1e-8f) len = 1;
			outP[i * 3] = px; outP[i * 3 + 1] = py; outP[i * 3 + 2] = pz;
			outN[i * 3] = qx / len; outN[i * 3 + 1] = qy / len; outN[i * 3 + 2] = qz / len;
		}
	}

	private static void emit(SkinnedMesh.Sub s, float[] p, float[] n, PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color) {
		int[] idx = s.i();
		float[] uv = s.uv();
		for (int t = 0; t + 2 < idx.length; t += 3) {
			for (int k : new int[]{idx[t], idx[t + 1], idx[t + 2], idx[t + 2]}) {
				vc.addVertex(pose, p[k * 3], p[k * 3 + 1], p[k * 3 + 2]).setColor(color).setUv(uv[k * 2], uv[k * 2 + 1])
						.setOverlay(overlay).setLight(light).setNormal(pose, n[k * 3], n[k * 3 + 1], n[k * 3 + 2]);
			}
		}
	}

	public static int noOverlay() {
		return OverlayTexture.NO_OVERLAY;
	}
}
