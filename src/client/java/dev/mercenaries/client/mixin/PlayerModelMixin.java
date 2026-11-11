package dev.mercenaries.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import dev.mercenaries.client.render.MercPlayerLayer;

/**
 * Hides the vanilla player cubes (skin, hat, jacket...) when a TF2 mesh replaces them. Model drawing is
 * deferred, so this must happen in setupAnim (called with each player's own render state at draw time).
 * Parts keep their transforms so held items and armor layers still line up.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"), require = 0)
	private void mercenaries$hide(AvatarRenderState state, CallbackInfo ci) {
		MercPlayerLayer.Info info = state.getData(MercPlayerLayer.INFO);
		boolean hide = info != null;
		for (ModelPart part : ((PlayerModel) (Object) this).root().getAllParts()) {
			part.skipDraw = hide;
		}
	}
}
