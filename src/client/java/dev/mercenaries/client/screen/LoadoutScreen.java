package dev.mercenaries.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.mercenaries.MercClass;
import dev.mercenaries.MercConfig;
import dev.mercenaries.MercTeam;
import dev.mercenaries.Mercenaries;
import dev.mercenaries.data.MercData;
import dev.mercenaries.net.MercPayloads;
import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.Weapons;

/**
 * Team, class and loadout picker. Opened with the "Mercenary loadout" key (default M).
 * Choosing "None" returns to plain Minecraft.
 */
public class LoadoutScreen extends Screen {
	private static final WeaponDef.Slot[] SLOTS = {WeaponDef.Slot.PRIMARY, WeaponDef.Slot.SECONDARY, WeaponDef.Slot.MELEE};

	private MercTeam team;
	private MercClass cls;
	private final int[] choice = new int[3];
	private final boolean firstTime;
	private final List<Button> classButtons = new ArrayList<>();
	private Button redButton, bluButton;

	public LoadoutScreen(boolean firstTime) {
		super(Component.literal("Choose your mercenary"));
		this.firstTime = firstTime;
		MercData.Loadout l = Minecraft.getInstance().player != null ? MercData.loadout(Minecraft.getInstance().player) : MercData.Loadout.NONE;
		team = l.mercTeam();
		cls = l.active() ? l.mercClass() : MercClass.SOLDIER;
		if (l.active()) {
			for (int i = 0; i < 3; i++) {
				List<WeaponDef> opts = Weapons.options(cls, SLOTS[i]);
				choice[i] = Math.max(0, opts.indexOf(Weapons.get(l.slot(i))));
			}
		}
	}

	private int left() {
		return width / 2 - 200;
	}

	@Override
	protected void init() {
		classButtons.clear();
		int x0 = left(), y0 = 40;

		redButton = addRenderableWidget(Button.builder(Component.literal("RED"), b -> { team = MercTeam.RED; refresh(); })
				.pos(x0, y0).size(58, 20).build());
		bluButton = addRenderableWidget(Button.builder(Component.literal("BLU"), b -> { team = MercTeam.BLU; refresh(); })
				.pos(x0 + 62, y0).size(58, 20).build());

		MercClass[] all = MercClass.values();
		for (int i = 1; i < all.length; i++) {
			MercClass c = all[i];
			int col = (i - 1) % 3, row = (i - 1) / 3;
			Button b = addRenderableWidget(Button.builder(Component.literal(c.displayName()), btn -> {
				cls = c;
				choice[0] = choice[1] = choice[2] = 0;
				rebuildWidgets();
			}).pos(x0 + col * 40 + 0, y0 + 30 + row * 44).size(38, 40).build());
			classButtons.add(b);
		}

		int rx = x0 + 140;
		for (int s = 0; s < 3; s++) {
			final int slot = s;
			int y = y0 + 30 + s * 52;
			addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(slot, -1)).pos(rx, y).size(20, 20).build());
			addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(slot, 1)).pos(rx + 240, y).size(20, 20).build());
		}

		int by = y0 + 30 + 3 * 52 + 10;
		addRenderableWidget(Button.builder(Component.literal("Deploy"), b -> apply(cls)).pos(rx, by).size(126, 20).build());
		addRenderableWidget(Button.builder(Component.literal("None (vanilla)"), b -> apply(MercClass.NONE)).pos(rx + 134, by).size(126, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).pos(rx + 70, by + 24).size(120, 20).build());
		refresh();
	}

	private void refresh() {
		if (redButton != null) {
			redButton.active = team != MercTeam.RED;
			bluButton.active = team != MercTeam.BLU;
		}
		MercClass[] all = MercClass.values();
		for (int i = 0; i < classButtons.size(); i++) classButtons.get(i).active = all[i + 1] != cls;
	}

	private void cycle(int slot, int dir) {
		List<WeaponDef> opts = Weapons.options(cls, SLOTS[slot]);
		if (opts.isEmpty()) return;
		choice[slot] = Math.floorMod(choice[slot] + dir, opts.size());
	}

	private WeaponDef chosen(int slot) {
		List<WeaponDef> opts = Weapons.options(cls, SLOTS[slot]);
		return opts.isEmpty() ? null : opts.get(Math.min(choice[slot], opts.size() - 1));
	}

	private void apply(MercClass c) {
		String[] ids = new String[3];
		for (int i = 0; i < 3; i++) {
			WeaponDef d = chosen(i);
			ids[i] = d == null ? "" : d.id();
		}
		ClientPlayNetworking.send(new MercPayloads.Select(team.ordinal(), c.ordinal(), ids[0], ids[1], ids[2]));
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		this.extractBackground(g, mouseX, mouseY, delta);
		super.extractRenderState(g, mouseX, mouseY, delta);
		int x0 = left(), y0 = 40;
		g.centeredText(font, title, width / 2, 12, CommonColors.WHITE);
		if (firstTime) g.centeredText(font, "You can change this any time with the loadout key (M).", width / 2, 24, 0xFFAAAAAA);

		// class icons from the extracted pack, if present
		MercClass[] all = MercClass.values();
		for (int i = 1; i < all.length; i++) {
			Identifier icon = Mercenaries.id("textures/gui/class/" + all[i].id() + ".png");
			if (Minecraft.getInstance().getResourceManager().getResource(icon).isPresent()) {
				int col = (i - 1) % 3, row = (i - 1) / 3;
				g.blit(RenderPipelines.GUI_TEXTURED, icon, x0 + col * 40 + 11, y0 + 30 + row * 44 + 4, 0f, 0f, 16, 16, 16, 16);
			}
		}

		// team bar + class info
		g.fill(x0, y0 + 166, x0 + 120, y0 + 168, team.argb);
		g.text(font, team.label + " " + cls.displayName(), x0, y0 + 172, team.argb, true);
		g.text(font, "Health " + cls.tf2Health + " (" + (int) cls.maxHealth() / 2 + " hearts)", x0, y0 + 184, 0xFFDDDDDD, false);
		g.text(font, "Speed " + Math.round(cls.speedMultiplier() * 100) + "%", x0, y0 + 196, 0xFFDDDDDD, false);
		int ly = y0 + 208;
		for (String line : wrap(cls.blurb, 120)) {
			g.text(font, line, x0, ly, 0xFFAAAAAA, false);
			ly += 10;
		}

		// loadout slots
		int rx = x0 + 140;
		String[] labels = {"PRIMARY", "SECONDARY", "MELEE"};
		for (int s = 0; s < 3; s++) {
			int y = y0 + 30 + s * 52;
			WeaponDef d = chosen(s);
			g.fill(rx + 22, y, rx + 238, y + 46, 0xA0101010);
			g.text(font, labels[s], rx + 26, y - 9, 0xFFC8A15A, false);
			if (d == null) continue;
			int n = Weapons.options(cls, SLOTS[s]).size();
			g.text(font, d.name() + (n > 1 ? "  (" + (choice[s] + 1) + "/" + n + ")" : ""), rx + 26, y + 4, CommonColors.WHITE, true);
			int dy = y + 16;
			for (String line : wrap(d.description(), 206)) {
				if (dy > y + 38) break;
				g.text(font, line, rx + 26, dy, 0xFFBBBBBB, false);
				dy += 10;
			}
			String stats = stats(d);
			if (!stats.isEmpty() && dy <= y + 36) g.text(font, stats, rx + 26, y + 36, 0xFF888888, false);
		}
		if (cls == MercClass.ENGINEER) g.text(font, "+ Construction PDA (sentry & dispenser)", rx + 26, y0 + 30 + 3 * 52 - 4, 0xFF888888, false);
	}

	private static String stats(WeaponDef d) {
		StringBuilder sb = new StringBuilder();
		if (d.damage() > 0) {
			double mc = MercConfig.dmg(d.damage() * d.pellets());
			sb.append("Dmg ").append(Math.round(d.damage() * d.pellets())).append(String.format(" (%.1f♥)", mc / 2));
		}
		if (d.clip() > 0) sb.append("  Clip ").append(d.clip()).append("/").append(d.reserve());
		else if (d.reserve() > 0) sb.append("  Ammo ").append(d.reserve());
		return sb.toString();
	}

	private List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String test = line.isEmpty() ? word : line + " " + word;
			if (font.width(test) > width && !line.isEmpty()) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(test);
			}
		}
		if (!line.isEmpty()) out.add(line.toString());
		return out;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
