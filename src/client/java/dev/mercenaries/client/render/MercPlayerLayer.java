package dev.mercenaries.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/**
 * Draws the extracted TF2 class mesh in place of the vanilla player model. Each mesh part follows
 * the matching vanilla model part, so walking, swinging, sneaking and looking all animate it.
 * The vanilla parts are hidden by {@link dev.mercenaries.client.mixin.PlayerModelMixin}.
 */
public class MercPlayerLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** Present on the render state only when a mesh is available for this player's class. */
	public static final RenderStateDataKey<Info> INFO = RenderStateDataKey.create(() -> "mercenaries player mesh");

	public record Info(String meshKey, int skin, boolean cloaked) {
	}

	public MercPlayerLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		Info info = state.getData(INFO);
		if (info == null || info.cloaked()) return;
		MercMesh mesh = MercMeshes.INSTANCE.get(info.meshKey());
		if (mesh == null) return;
		PlayerModel model = getParentModel();
		model.setupAnim(state);
		int overlay = OverlayTexture.NO_OVERLAY;
		draw(mesh, "head", model.head, poseStack, collector, info.skin(), light, overlay);
		draw(mesh, "body", model.body, poseStack, collector, info.skin(), light, overlay);
		draw(mesh, "right_arm", model.rightArm, poseStack, collector, info.skin(), light, overlay);
		draw(mesh, "left_arm", model.leftArm, poseStack, collector, info.skin(), light, overlay);
		draw(mesh, "right_leg", model.rightLeg, poseStack, collector, info.skin(), light, overlay);
		draw(mesh, "left_leg", model.leftLeg, poseStack, collector, info.skin(), light, overlay);
	}

	private static void draw(MercMesh mesh, String name, ModelPart part, PoseStack ps, SubmitNodeCollector c, int skin, int light, int overlay) {
		MercMesh.Part p = mesh.parts().get(name);
		if (p == null) return;
		PartPose init = part.getInitialPose();
		ps.pushPose();
		// TF2 joint position + whatever offset vanilla's animation applied (e.g. sneaking)
		ps.translate((p.pivot()[0] + part.x - init.x()) / 16f, (p.pivot()[1] + part.y - init.y()) / 16f, (p.pivot()[2] + part.z - init.z()) / 16f);
		// same order as ModelPart.translateAndRotate: Z, then Y, then X
		if (part.zRot != 0) ps.rotate(Axis.ZP, part.zRot);
		if (part.yRot != 0) ps.rotate(Axis.YP, part.yRot);
		if (part.xRot != 0) ps.rotate(Axis.XP, part.xRot);
		ps.scale(part.xScale, part.yScale, part.zScale);
		mesh.submitPart(p, ps, c, skin, light, overlay, 0xFFFFFFFF);
		ps.popPose();
	}
}
