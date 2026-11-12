package dev.mercenaries.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;

import dev.mercenaries.client.render.MercMeshes;
import dev.mercenaries.client.render.MercPlayerLayer;
import dev.mercenaries.data.MercData;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;

/** Copies the player's mercenary class, team and movement onto the render state (render states can't see entities). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin<AvatarlikeEntity extends Avatar & ClientAvatarEntity> {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("RETURN"))
	private void mercenaries$extract(AvatarlikeEntity entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		MercData.Loadout l = MercData.loadout(entity);
		String key = "player/" + l.mercClass().id();
		if (!l.active() || MercMeshes.INSTANCE.skinned(key) == null) {
			state.setData(MercPlayerLayer.INFO, null);
			return;
		}
		double dx = entity.getX() - entity.xo, dz = entity.getZ() - entity.zo;
		float speed = (float) Math.sqrt(dx * dx + dz * dz);
		ItemStack held = entity.getMainHandItem();
		String weaponId = null, slot = "melee";
		if (held.getItem() instanceof MercWeaponItem w) {
			WeaponDef d = w.def();
			weaponId = d.id();
			String s = MercMeshes.INSTANCE.animSlot(weaponId);
			slot = s != null ? s : switch (d.slot()) {
				case PRIMARY -> "primary";
				case SECONDARY -> "secondary";
				case MELEE -> "melee";
				case PDA -> "pda";
			};
		}
		float refSpeed = (float) (0.28 * l.mercClass().speedMultiplier());
		state.setData(MercPlayerLayer.INFO, new MercPlayerLayer.Info(key, l.mercTeam().skin, MercData.status(entity).cloaked(),
				entity.getId(), (entity.tickCount + partialTicks) / 20f, speed, refSpeed,
				entity.onGround(), entity.isCrouching(), entity.isSwimming() || entity.isInWater() && !entity.onGround(),
				weaponId, slot));
	}
}
