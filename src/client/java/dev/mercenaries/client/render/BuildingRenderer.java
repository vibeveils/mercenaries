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

import dev.mercenaries.entity.BuildingEntity;

/**
 * Renders sentries/dispensers with the extracted TF2 mesh when available, otherwise with a simple
 * original block-built stand-in tinted in team colour.
 */
public class BuildingRenderer extends EntityRenderer<BuildingEntity, BuildingRenderer.State> {
	private static final Identifier FALLBACK_TEX = Identifier.withDefaultNamespace("textures/block/iron_block.png");

	public static class State extends EntityRenderState {
		BuildingEntity.Kind kind = BuildingEntity.Kind.SENTRY;
		int skin;
		int teamColor = 0xFFFFFFFF;
		float yaw, pitch;
		int level = 1;
		boolean sapped;
	}

	public BuildingRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(BuildingEntity e, State s, float partialTick) {
		super.extractRenderState(e, s, partialTick);
		s.kind = e.kind();
		s.skin = e.team().skin;
		s.teamColor = e.team().argb;
		s.yaw = e.getYHeadRot();
		s.pitch = e.getXRot();
		s.level = e.buildingLevel();
		s.sapped = e.isSapped();
	}

	@Override
	public void submit(State s, PoseStack ps, SubmitNodeCollector c, CameraRenderState camera) {
		super.submit(s, ps, c, camera);
		String key = s.kind == BuildingEntity.Kind.SENTRY ? "prop/sentry" : "prop/dispenser";
		MercMesh mesh = MercMeshes.INSTANCE.get(key);
		int light = s.lightCoords;
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(180f - s.yaw));
		if (mesh != null) {
			// building meshes are exported centred on x/z with their base at y = 0, longest side = 1 block
			ps.scale(1.1f, 1.1f, 1.1f);
			mesh.submitAll(ps, c, s.skin, light, OverlayTexture.NO_OVERLAY, s.sapped ? 0xFF8899FF : 0xFFFFFFFF);
		} else {
			int col = s.sapped ? 0xFF7788FF : s.teamColor;
			c.submitCustomGeometry(ps, RenderTypes.entityCutoutNoCull(FALLBACK_TEX), (pose, vc) -> {
				if (s.kind == BuildingEntity.Kind.SENTRY) {
					box(pose, vc, -0.35f, 0f, -0.35f, 0.35f, 0.15f, 0.35f, col, light);      // base plate
					box(pose, vc, -0.07f, 0.15f, -0.07f, 0.07f, 0.55f, 0.07f, col, light);   // post
					box(pose, vc, -0.22f, 0.55f, -0.25f, 0.22f, 0.85f, 0.2f, col, light);    // housing
					for (int i = 0; i < s.level; i++) {
						float x = -0.12f + i * 0.12f;
						box(pose, vc, x - 0.03f, 0.66f, 0.2f, x + 0.03f, 0.72f, 0.6f, 0xFF333333, light); // barrels
					}
				} else {
					box(pose, vc, -0.4f, 0f, -0.3f, 0.4f, 1.0f + 0.1f * s.level, 0.3f, col, light);
					box(pose, vc, -0.3f, 0.5f, 0.3f, 0.3f, 0.8f, 0.32f, 0xFF66CC66, light); // status panel
				}
			});
		}
		ps.popPose();
	}

	private static void box(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int color, int light) {
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
