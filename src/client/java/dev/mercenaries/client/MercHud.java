package dev.mercenaries.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import dev.mercenaries.MercClass;
import dev.mercenaries.MercConfig;
import dev.mercenaries.MercTeam;
import dev.mercenaries.Mercenaries;
import dev.mercenaries.data.MercData;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;

/**
 * TF2-style HUD: health cross (bottom left), ammo panel (bottom right), Über / cloak / metal / charge
 * meters. Uses TF2's own HUD images when the extracted pack provides them (textures/gui/tf2/*),
 * otherwise draws plain shapes. Vanilla hunger, armour, XP and hotbar are left alone.
 */
public final class MercHud {
	private MercHud() {
	}

	private static final Map<String, Boolean> PRESENT = new HashMap<>();

	static Identifier gui(String key) {
		return Mercenaries.id("textures/gui/tf2/" + key + ".png");
	}

	/** True if the extracted pack supplied this HUD image (cached; reset on resource reload). */
	static boolean has(String key) {
		return PRESENT.computeIfAbsent(key, k -> Minecraft.getInstance().getResourceManager().getResource(gui(k)).isPresent());
	}

	public static void resetCache() {
		PRESENT.clear();
	}

	static boolean active() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p != null && MercData.loadout(p).active();
	}

	/** Replaces vanilla hearts while playing a class. */
	public static HudElement healthReplacement(HudElement vanilla) {
		return (g, t) -> {
			if (!active()) vanilla.extractRenderState(g, t);
		};
	}

	/** Uses TF2's crosshair while holding a ranged merc weapon. */
	public static HudElement crosshairReplacement(HudElement vanilla) {
		return (g, t) -> {
			LocalPlayer p = Minecraft.getInstance().player;
			boolean ranged = p != null && active() && p.getMainHandItem().getItem() instanceof MercWeaponItem w && w.def().isRanged();
			if (ranged && has("crosshair")) {
				int s = 24;
				g.blit(RenderPipelines.GUI_TEXTURED, gui("crosshair"), g.guiWidth() / 2 - s / 2, g.guiHeight() / 2 - s / 2, 0f, 0f, s, s, s, s);
			} else {
				vanilla.extractRenderState(g, t);
			}
		};
	}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		MercData.Loadout l = MercData.loadout(p);
		if (!l.active()) return;
		MercData.Status s = MercData.status(p);
		Font font = mc.font;
		MercTeam team = l.mercTeam();
		String t = team == MercTeam.BLU ? "blu" : "red";
		int w = g.guiWidth(), h = g.guiHeight();

		// ---------------------------------------------------------- health (bottom left)
		int hx = 8, hy = h - 58, hs = 50;
		String portrait = "portrait_" + l.mercClass().id() + "_" + t;
		if (has(portrait)) {
			g.blit(RenderPipelines.GUI_TEXTURED, gui(portrait), hx, hy - 6, 0f, 0f, 44, 56, 44, 56);
			hx += 40;
		}
		float frac = Math.max(0, Math.min(1, p.getHealth() / p.getMaxHealth()));
		int tf2Health = (int) Math.ceil(p.getHealth() * MercConfig.get().hpScale());
		int crossColor = frac < 0.5f ? 0xFFE04040 : 0xFFEBE2CA;
		if (has("health_bg") && has("health_color")) {
			if (p.getAbsorptionAmount() > 0 && has("health_over")) {
				g.blit(RenderPipelines.GUI_TEXTURED, gui("health_over"), hx - 4, hy - 4, 0f, 0f, hs + 8, hs + 8, hs + 8, hs + 8);
			}
			g.blit(RenderPipelines.GUI_TEXTURED, gui("health_bg"), hx, hy, 0f, 0f, hs, hs, hs, hs);
			int off = Math.round(hs * (1 - frac));
			if (hs - off > 0) g.blit(RenderPipelines.GUI_TEXTURED, gui("health_color"), hx, hy + off, 0f, (float) off, hs, hs - off, hs, hs);
		} else {
			int arm = hs / 3;
			g.fill(hx + arm, hy, hx + 2 * arm, hy + hs, 0xC0202020);
			g.fill(hx, hy + arm, hx + hs, hy + 2 * arm, 0xC0202020);
			int top = hy + Math.round(hs * (1 - frac));
			g.fill(hx + arm + 2, Math.max(top, hy + 2), hx + 2 * arm - 2, hy + hs - 2, crossColor);
			g.fill(hx + 2, Math.max(top, hy + arm + 2), hx + hs - 2, hy + 2 * arm - 2, crossColor);
		}
		bigNumber(g, font, String.valueOf(tf2Health), hx + hs / 2, hy + hs / 2 - 6, 0xFFFFFFFF, true);

		// ---------------------------------------------------------- ammo (bottom right)
		int ax = w - 118, ay = h - 50, aw = 110, ah = 42;
		ItemStack held = p.getMainHandItem();
		WeaponDef d = held.getItem() instanceof MercWeaponItem wi ? wi.def() : null;
		if (d != null && d.usesAmmo()) {
			if (has("ammo_" + t)) g.blit(RenderPipelines.GUI_TEXTURED, gui("ammo_" + t), ax, ay, 0f, 0f, aw, ah, aw, ah);
			else g.fill(ax, ay, ax + aw, ay + ah, (team.argb & 0x00FFFFFF) | 0xB0000000);
			if (d.clip() > 0) {
				bigNumber(g, font, String.valueOf(MercWeaponItem.clip(held)), ax + 38, ay + 12, 0xFFFFFFFF, true);
				g.text(font, String.valueOf(MercWeaponItem.reserve(held)), ax + 70, ay + 22, 0xFFEBE2CA, true);
			} else {
				bigNumber(g, font, String.valueOf(MercWeaponItem.reserve(held)), ax + aw / 2, ay + 12, 0xFFFFFFFF, true);
			}
		}

		// ---------------------------------------------------------- class meters (above ammo)
		int my = ay - 26;
		if (d != null && s.charge() > 0) my = meter(g, font, ax, my, aw, s.charge(), 0xFFEBE2CA, d.kind() == WeaponDef.Kind.MINIGUN ? "SPIN" : "CHARGE", t);
		if (d != null && (d.kind() == WeaponDef.Kind.CONSUMABLE || d.kind() == WeaponDef.Kind.JAR)) my = meter(g, font, ax, my, aw, s.cooldown(), 0xFF8BD18B, "RECHARGE", t);
		if (d != null && d.kind() == WeaponDef.Kind.STICKY) my = label(g, font, ax, my, aw, "STICKIES  " + s.stickies(), t);
		switch (l.mercClass()) {
			case MEDIC -> my = meter(g, font, ax, my, aw, s.uber(), s.uber() >= 1f ? 0xFFFFFFFF : 0xFF8FB5FF,
					s.uberTicks() > 0 ? (s.kritz() ? "KRITZ!" : "ÜBER!") : "ÜBERCHARGE " + Math.round(s.uber() * 100) + "%", t);
			case ENGINEER -> my = label(g, font, ax, my, aw, "METAL  " + s.metal(), t);
			case SPY -> my = meter(g, font, ax, my, aw, s.cloak(), s.cloaked() ? 0xFF9F9FFF : 0xFFBBBBBB, s.cloaked() ? "CLOAKED" : "CLOAK", t);
			default -> {
			}
		}
		if (s.uberTicks() > 0) {
			g.centeredText(font, s.kritz() ? "CRIT BOOSTED" : "INVULNERABLE", w / 2, h / 2 + 22, s.kritz() ? 0xFFFF5050 : 0xFF80B0FF);
		}
	}

	private static int meter(GuiGraphicsExtractor g, Font font, int x, int y, int width, float frac, int color, String label, String team) {
		panel(g, x, y, width, 22, team);
		g.text(font, label, x + 6, y + 3, 0xFFEBE2CA, true);
		g.fill(x + 6, y + 13, x + width - 6, y + 18, 0xFF2A2A2A);
		g.fill(x + 6, y + 13, x + 6 + Math.round((width - 12) * Math.max(0, Math.min(1, frac))), y + 18, color);
		return y - 24;
	}

	private static int label(GuiGraphicsExtractor g, Font font, int x, int y, int width, String text, String team) {
		panel(g, x, y, width, 22, team);
		g.text(font, text, x + 6, y + 7, 0xFFEBE2CA, true);
		return y - 24;
	}

	private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, String team) {
		String key = "misc_" + team;
		if (has(key)) g.blit(RenderPipelines.GUI_TEXTURED, gui(key), x, y, 0f, 0f, w, h, w, h);
		else g.fill(x, y, x + w, y + h, 0xA0141414);
	}

	private static void bigNumber(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().translate(cx, y);
		g.pose().scale(2f);
		g.text(font, text, -font.width(text) / 2, 0, color, shadow);
		g.pose().popMatrix();
	}
}
