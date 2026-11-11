package dev.mercenaries;

import net.minecraft.ChatFormatting;

public enum MercTeam {
	RED("RED", ChatFormatting.RED, 0xFFB8383B, 0),
	BLU("BLU", ChatFormatting.BLUE, 0xFF5885A2, 1);

	public final String label;
	public final ChatFormatting chat;
	public final int argb;
	/** Skin family index in TF2 models (0 = red, 1 = blue). */
	public final int skin;

	MercTeam(String label, ChatFormatting chat, int argb, int skin) {
		this.label = label;
		this.chat = chat;
		this.argb = argb;
		this.skin = skin;
	}

	public String scoreboardName() {
		return "merc_" + name().toLowerCase();
	}

	public MercTeam other() {
		return this == RED ? BLU : RED;
	}

	public static MercTeam byOrdinal(int i) {
		return i == 1 ? BLU : RED;
	}
}
