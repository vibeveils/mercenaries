package dev.mercenaries.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

import dev.mercenaries.client.render.MercMeshes;
import dev.mercenaries.client.render.MercPlayerLayer;
import dev.mercenaries.data.MercData;

/** Copies the player's mercenary class/team onto the render state (render states can't see entities). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin<AvatarlikeEntity extends Avatar & ClientAvatarEntity> {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("RETURN"))
	private void mercenaries$extract(AvatarlikeEntity entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		MercData.Loadout l = MercData.loadout(entity);
		String key = "player/" + l.mercClass().id();
		if (l.active() && MercMeshes.INSTANCE.get(key) != null) {
			state.setData(MercPlayerLayer.INFO, new MercPlayerLayer.Info(key, l.mercTeam().skin, MercData.status(entity).cloaked()));
		} else {
			state.setData(MercPlayerLayer.INFO, null);
		}
	}
}
