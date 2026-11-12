package dev.mercenaries.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import dev.mercenaries.entity.MercProjectile;

/**
 * Draws projectiles with the extracted TF2 mesh ("prop/rocket" etc.) when the pack is enabled,
 * otherwise as a small tinted capsule. Oriented along the direction of travel.
 */
public class ProjectileRenderer extends EntityRenderer<MercProjectile, ProjectileRenderer.State> {
	private static final Identifier FALLBACK_TEX = Identifier.withDefaultNamespace("textures/block/white_concrete.png");

	public static class State extends EntityRenderState {
		MercProjectile.Type type = MercProjectile.Type.ROCKET;
		float yaw, pitch;
	}

	public ProjectileRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(MercProjectile e, State s, float partialTick) {
		super.extractRenderState(e, s, partialTick);
		s.type = e.kind();
		double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
		if (dx * dx + dy * dy + dz * dz > 1e-6) {
			s.yaw = (float) (Mth.atan2(dx, dz) * Mth.RAD_TO_DEG);
			s.pitch = (float) (Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
		}
	}

	@Override
	public void submit(State s, PoseStack ps, SubmitNodeCollector c, CameraRenderState camera) {
		super.submit(s, ps, c, camera);
		ps.pushPose();
		ps.rotate(Axis.YP, (s.yaw) * Mth.DEG_TO_RAD);
		ps.rotate(Axis.XP, (-s.pitch) * Mth.DEG_TO_RAD);
		int light = s.lightCoords;
		MercMesh mesh = MercMeshes.INSTANCE.get("prop/" + key(s.type));
		if (mesh != null) {
			// prop meshes are exported in item space: centred at (0.5,0.5,0.5), nose toward -z, longest side 1
			float size = s.type == MercProjectile.Type.ROCKET || s.type == MercProjectile.Type.ARROW ? 0.6f : 0.3f;
			ps.rotate(Axis.YP, (180f) * Mth.DEG_TO_RAD);
			ps.scale(size, size, size);
			ps.translate(-0.5f, -0.5f, -0.5f);
			mesh.submitAll(ps, c, 0, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
		} else {
			int col = switch (s.type) {
				case ROCKET -> 0xFF8A8A8A;
				case PIPE -> 0xFF4E6A3C;
				case STICKY -> 0xFF3C3C8C;
				case FLARE -> 0xFFFF8A28;
				case ARROW -> 0xFF9A7A50;
				case JAR -> 0xFFE6D23C;
				case SYRINGE -> 0xFFB4C8E6;
			};
			float len = s.type == MercProjectile.Type.ROCKET || s.type == MercProjectile.Type.ARROW ? 0.25f : 0.09f;
			float w = s.type == MercProjectile.Type.SYRINGE || s.type == MercProjectile.Type.ARROW ? 0.03f : 0.08f;
			c.submitCustomGeometry(ps, RenderTypes.armorCutoutNoCull(FALLBACK_TEX), (pose, vc) -> box(pose, vc, -w, -w, -len, w, w, len, col, light));
		}
		ps.popPose();
	}

	private static String key(MercProjectile.Type t) {
		return switch (t) {
			case ROCKET -> "rocket";
			case PIPE -> "pipe";
			case STICKY -> "sticky";
			case FLARE -> "flare";
			case ARROW -> "arrow";
			case JAR -> "jar";
			case SYRINGE -> "syringe";
		};
	}

	static void box(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int color, int light) {
		float[][] faces = {
				{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1},
				{x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1},
				{x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0},
				{x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0},
				{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0},
				{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0},
		};
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		for (float[] f : faces) {
			for (int k = 0; k < 4; k++) {
				vc.addVertex(pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2]).setColor(color).setUv(uv[k][0], uv[k][1])
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, f[12], f[13], f[14]);
			}
		}
	}
}
