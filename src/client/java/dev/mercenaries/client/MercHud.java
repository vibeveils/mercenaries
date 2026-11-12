package dev.mercenaries.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import dev.mercenaries.MercClass;
import dev.mercenaries.data.MercData;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;

/** Bottom-right TF2-style readout: class, ammo, Über, metal, cloak, charge. */
public final class MercHud {
	private MercHud() {
	}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		MercData.Loadout l = MercData.loadout(p);
		if (!l.active()) return;
		MercData.Status s = MercData.status(p);
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		int x = w - 110, y = h - 70;

		g.fill(x - 4, y - 4, w - 4, h - 4, 0x90000000);
		g.text(font, l.mercTeam().label + " " + l.mercClass().displayName(), x, y, l.mercTeam().argb, true);
		y += 12;

		ItemStack held = p.getMainHandItem();
		if (held.getItem() instanceof MercWeaponItem w2) {
			WeaponDef d = w2.def();
			if (d.usesAmmo()) {
				String ammo = d.clip() > 0 ? MercWeaponItem.clip(held) + " / " + MercWeaponItem.reserve(held) : String.valueOf(MercWeaponItem.reserve(held));
				g.text(font, d.name(), x, y, 0xFFDDDDDD, false);
				y += 10;
				g.text(font, ammo, x, y, 0xFFF2C14E, true);
				y += 12;
			} else {
				g.text(font, d.name(), x, y, 0xFFDDDDDD, false);
				y += 12;
			}
			if (s.charge() > 0) {
				bar(g, x, y, 100, s.charge(), 0xFFE0E0E0, "Charge");
				y += 10;
			}
			if (d.kind() == WeaponDef.Kind.STICKY) {
				g.text(font, "Stickies: " + s.stickies(), x, y, 0xFFDDDDDD, false);
				y += 10;
			}
			if (d.kind() == WeaponDef.Kind.CONSUMABLE || d.kind() == WeaponDef.Kind.JAR) {
				bar(g, x, y, 100, s.cooldown(), 0xFF7FD17F, "Recharge");
				y += 10;
			}
		}
		if (l.mercClass() == MercClass.MEDIC) bar(g, x, y, 100, s.uber(), s.uber() >= 1f ? 0xFFFFFFFF : 0xFF8FB5FF, s.uberTicks() > 0 ? "ÜBER!" : "Über " + Math.round(s.uber() * 100) + "%");
		if (l.mercClass() == MercClass.ENGINEER) g.text(font, "Metal: " + s.metal(), x, y, 0xFFC8C8C8, false);
		if (l.mercClass() == MercClass.SPY) bar(g, x, y, 100, s.cloak(), s.cloaked() ? 0xFF9F9FFF : 0xFFBBBBBB, s.cloaked() ? "Cloaked" : "Cloak");

		if (s.uberTicks() > 0) g.centeredText(font, s.kritz() ? "CRIT BOOSTED" : "INVULNERABLE", w / 2, h / 2 + 20, s.kritz() ? 0xFFFF5050 : 0xFF80B0FF);
	}

	private static void bar(GuiGraphicsExtractor g, int x, int y, int width, float frac, int color, String label) {
		g.fill(x, y, x + width, y + 8, 0xFF303030);
		g.fill(x, y, x + Math.round(width * Math.max(0, Math.min(1, frac))), y + 8, color);
		g.text(Minecraft.getInstance().font, label, x + 2, y, 0xFF000000, false);
	}
}
