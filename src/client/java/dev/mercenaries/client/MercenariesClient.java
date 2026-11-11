package dev.mercenaries.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;

import dev.mercenaries.MercClass;
import dev.mercenaries.Mercenaries;
import dev.mercenaries.client.render.BuildingRenderer;
import dev.mercenaries.client.render.MercMeshes;
import dev.mercenaries.client.render.MercPlayerLayer;
import dev.mercenaries.client.render.MeshItemRenderer;
import dev.mercenaries.client.screen.LoadoutScreen;
import dev.mercenaries.data.MercData;
import dev.mercenaries.entity.MercEntities;
import dev.mercenaries.net.MercPayloads;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;

public final class MercenariesClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Mercenaries.id("mercenaries"));
	public static final KeyMapping LOADOUT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.mercenaries.loadout", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_M, CATEGORY));
	public static final KeyMapping RELOAD = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.mercenaries.reload", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_R, CATEGORY));
	public static final KeyMapping ABILITY = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.mercenaries.ability", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_V, CATEGORY));

	private int lastButtons = -1;
	private int sendTimer;
	private boolean doubleJumped;
	private boolean jumpWasDown;

	@Override
	public void onInitializeClient() {
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(Mercenaries.id("meshes"), MercMeshes.INSTANCE);
		SpecialModelRenderers.ID_MAPPER.put(Mercenaries.id("merc_mesh"), MeshItemRenderer.Unbaked.MAP_CODEC);

		EntityRendererRegistry.register(MercEntities.PROJECTILE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(MercEntities.BUILDING, BuildingRenderer::new);
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer avatar) helper.register(new MercPlayerLayer(avatar));
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Mercenaries.id("hud"), MercHud::extract);

		ClientPlayNetworking.registerGlobalReceiver(MercPayloads.OpenPicker.TYPE, (payload, ctx) ->
				ctx.client().execute(() -> ctx.client().setScreen(new LoadoutScreen(payload.firstTime()))));

		// Holding a ranged merc weapon: left mouse fires instead of attacking/mining. Switch to another
		// hotbar slot to mine and build as normal.
		ClientPreAttackCallback.EVENT.register((client, player, clicks) -> rangedHeld(player) != null);

		ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
			if (!(stack.getItem() instanceof MercWeaponItem w)) return;
			WeaponDef d = w.def();
			lines.add(Component.literal(d.mercClass().displayName() + " · " + d.slot().name().toLowerCase()).withStyle(ChatFormatting.GOLD));
			lines.add(Component.literal(d.description()).withStyle(ChatFormatting.GRAY));
			if (d.usesAmmo()) {
				lines.add(Component.literal(d.clip() > 0 ? "Ammo " + MercWeaponItem.clip(stack) + " / " + MercWeaponItem.reserve(stack)
						: "Ammo " + MercWeaponItem.reserve(stack)).withStyle(ChatFormatting.DARK_GRAY));
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private static WeaponDef rangedHeld(LocalPlayer p) {
		if (p == null) return null;
		ItemStack s = p.getMainHandItem();
		return s.getItem() instanceof MercWeaponItem w && w.def().isRanged() ? w.def() : null;
	}

	private void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.getConnection() == null) return;

		while (LOADOUT.consumeClick()) mc.setScreen(new LoadoutScreen(false));
		while (RELOAD.consumeClick()) ClientPlayNetworking.send(new MercPayloads.Action(MercPayloads.Action.RELOAD));
		while (ABILITY.consumeClick()) ClientPlayNetworking.send(new MercPayloads.Action(MercPayloads.Action.ABILITY));

		// mouse state for ranged weapons
		WeaponDef def = rangedHeld(p);
		int buttons = 0;
		if (def != null && mc.screen == null) {
			if (mc.options.keyAttack.isDown()) buttons |= 1;
			if (mc.options.keyUse.isDown()) buttons |= 2;
			if (def.kind() == WeaponDef.Kind.BUILDER && p.isShiftKeyDown() && (buttons & 1) != 0 && (lastButtons & 1) == 0) {
				ClientPlayNetworking.send(new MercPayloads.Action(MercPayloads.Action.DEMOLISH));
				buttons &= ~1;
			}
		}
		if (buttons != lastButtons || (buttons != 0 && ++sendTimer >= 10)) {
			ClientPlayNetworking.send(new MercPayloads.Input(buttons));
			lastButtons = buttons;
			sendTimer = 0;
		}

		// Scout double jump (movement is client-authoritative in Minecraft)
		MercData.Loadout l = MercData.loadout(p);
		boolean jumpDown = mc.options.keyJump.isDown();
		if (p.onGround()) doubleJumped = false;
		if (l.mercClass() == MercClass.SCOUT && jumpDown && !jumpWasDown && !p.onGround() && !doubleJumped
				&& !p.isInWater() && !p.getAbilities().flying && !p.isPassenger()) {
			doubleJumped = true;
			float yaw = p.getYRot() * ((float) Math.PI / 180f);
			Vec3 input = new Vec3(p.xxa, 0, p.zza);
			Vec3 push = Vec3.ZERO;
			if (input.lengthSqr() > 1e-4) {
				input = input.normalize();
				double sin = Math.sin(yaw), cos = Math.cos(yaw);
				push = new Vec3(input.x * cos - input.z * sin, 0, input.z * cos + input.x * sin).scale(0.35);
			}
			Vec3 v = p.getDeltaMovement();
			p.setDeltaMovement(push.lengthSqr() > 0 ? push.x : v.x * 0.5, 0.48, push.lengthSqr() > 0 ? push.z : v.z * 0.5);
			p.resetFallDistance();
		}
		jumpWasDown = jumpDown;
	}
}
