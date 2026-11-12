package dev.mercenaries.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/**
 * Draws the player's TF2 class mesh, skeletally animated with the class's own TF2 animations
 * (stand / run / crouch / air / swim for the held weapon's animation slot), plus the held weapon
 * bone-merged onto the hand. The vanilla player cubes are hidden by PlayerModelMixin.
 */
public class MercPlayerLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	public static final RenderStateDataKey<Info> INFO = RenderStateDataKey.create(() -> "mercenaries player mesh");

	/**
	 * @param time      seconds of entity age (smooth, includes partial tick)
	 * @param speed     horizontal speed in blocks per tick
	 * @param refSpeed  this class's normal run speed in blocks per tick
	 */
	public record Info(String meshKey, int skin, boolean cloaked, int entityId, float time, float speed, float refSpeed,
					   boolean onGround, boolean crouching, boolean swimming, String weaponId, String animSlot) {
	}

	public MercPlayerLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		Info info = state.getData(INFO);
		if (info == null || info.cloaked()) return;
		SkinnedMesh mesh = MercMeshes.INSTANCE.skinned(info.meshKey());
		if (mesh == null) return;

		// head look comes from vanilla's model so it matches what everyone expects
		PlayerModel model = getParentModel();
		model.setupAnim(state);
		float headYaw = model.head.yRot, headPitch = model.head.xRot;

		String movement;
		if (info.swimming()) movement = "swim";
		else if (!info.onGround()) movement = "air";
		else if (info.crouching()) movement = info.speed() > 0.01f ? "crouch_walk" : "crouch";
		else movement = info.speed() > 0.02f ? "run" : "stand";
		String clip = Animator.chooseClip(mesh, movement, info.animSlot());
		float rate = movement.equals("run") || movement.equals("crouch_walk")
				? Math.max(0.4f, Math.min(1.8f, info.speed() / Math.max(0.05f, info.refSpeed()))) : 1f;

		Animator anim = Animator.get(info.entityId(), mesh);
		anim.update(clip, info.time(), rate, headYaw, headPitch);
		anim.submitBody(poseStack, collector, info.skin(), light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

		if (info.weaponId() != null) {
			WeaponAttach w = MercMeshes.INSTANCE.attach(info.weaponId());
			if (w != null) anim.submitWeapon(w, poseStack, collector, info.skin(), light, OverlayTexture.NO_OVERLAY);
		}
	}
}
