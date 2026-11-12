package dev.mercenaries.client.render;

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
 * A converted TF2 mesh. Players are split into Minecraft body parts (head, body, arms, legs) so
 * vanilla's walk/swing/sneak animation drives them; static meshes have a single "root" part.
 */
public record MercMesh(List<List<Identifier>> skins, List<String> modes, Map<String, Part> parts) {

	/** @param pivot pivot in model pixels (1/16 block), entity-model space */
	public record Part(float[] pivot, List<Sub> subs) {
	}

	/** Triangle list. v/n are xyz triplets, uv pairs, i indices into those. */
	public record Sub(int mat, float[] v, float[] n, float[] uv, int[] i) {
	}

	public Identifier texture(int skin, int mat) {
		if (skins.isEmpty()) return null;
		List<Identifier> fam = skins.get(Math.min(Math.max(0, skin), skins.size() - 1));
		Identifier t = mat < fam.size() ? fam.get(mat) : null;
		if (t == null && !skins.getFirst().isEmpty() && mat < skins.getFirst().size()) t = skins.getFirst().get(mat);
		return t;
	}

	private RenderType renderType(Identifier tex, int mat) {
		String mode = mat < modes.size() ? modes.get(mat) : "cutout";
		return "translucent".equals(mode) ? RenderTypes.entityTranslucent(tex) : RenderTypes.armorCutoutNoCull(tex);
	}

	/** Submits one part at the current pose. Triangles are emitted as degenerate quads. */
	public void submitPart(Part part, PoseStack poseStack, SubmitNodeCollector collector, int skin, int light, int overlay, int color) {
		for (Sub sub : part.subs()) {
			Identifier tex = texture(skin, sub.mat());
			if (tex == null) continue;
			collector.submitCustomGeometry(poseStack, renderType(tex, sub.mat()), (pose, vc) -> emit(sub, pose, vc, light, overlay, color));
		}
	}

	public void submitAll(PoseStack poseStack, SubmitNodeCollector collector, int skin, int light, int overlay, int color) {
		for (Part p : parts.values()) submitPart(p, poseStack, collector, skin, light, overlay, color);
	}

	private static void emit(Sub s, PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color) {
		int[] idx = s.i();
		for (int t = 0; t + 2 < idx.length; t += 3) {
			vertex(s, idx[t], pose, vc, light, overlay, color);
			vertex(s, idx[t + 1], pose, vc, light, overlay, color);
			vertex(s, idx[t + 2], pose, vc, light, overlay, color);
			vertex(s, idx[t + 2], pose, vc, light, overlay, color);
		}
	}

	private static void vertex(Sub s, int i, PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, int color) {
		float[] v = s.v(), n = s.n(), uv = s.uv();
		vc.addVertex(pose, v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
				.setColor(color)
				.setUv(uv[i * 2], uv[i * 2 + 1])
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(pose, n[i * 3], n[i * 3 + 1], n[i * 3 + 2]);
	}

	public static int noOverlay() {
		return OverlayTexture.NO_OVERLAY;
	}
}
