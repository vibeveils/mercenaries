package dev.mercenaries.weapon;


import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * One item per weapon. Firing is not done here: the client sends its mouse state every tick and
 * {@link dev.mercenaries.combat.WeaponHandler} fires on the server. Melee weapons use vanilla attacks.
 */
public class MercWeaponItem extends Item {
	private final WeaponDef def;

	public MercWeaponItem(WeaponDef def, Properties properties) {
		super(properties);
		this.def = def;
	}

	public WeaponDef def() {
		return def;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		// Ranged weapons use right-click as secondary fire (handled by WeaponHandler) - block the
		// off-hand item from also being used. Melee weapons let vanilla behave normally (e.g. shields).
		return def.isRanged() ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	public static int clip(ItemStack s) {
		Integer v = s.get(MercComponents.CLIP);
		return v == null ? 0 : v;
	}

	public static int reserve(ItemStack s) {
		Integer v = s.get(MercComponents.RESERVE);
		return v == null ? 0 : v;
	}

	public static void setAmmo(ItemStack s, int clip, int reserve) {
		s.set(MercComponents.CLIP, Math.max(0, clip));
		s.set(MercComponents.RESERVE, Math.max(0, reserve));
	}

	public void refill(ItemStack s) {
		setAmmo(s, def.clip(), def.reserve());
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return def.usesAmmo();
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		float frac = def.clip() > 0 ? clip(stack) / (float) def.clip() : reserve(stack) / (float) Math.max(1, def.reserve());
		return Mth.clamp(Math.round(13f * frac), 0, 13);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return 0xF2C14E;
	}
}
