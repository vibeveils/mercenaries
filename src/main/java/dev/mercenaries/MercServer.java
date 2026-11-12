package dev.mercenaries;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.mercenaries.combat.Combat;
import dev.mercenaries.combat.WeaponHandler;
import dev.mercenaries.data.MercData;
import dev.mercenaries.net.MercPayloads;
import dev.mercenaries.weapon.MercWeaponItem;
import dev.mercenaries.weapon.WeaponDef;
import dev.mercenaries.weapon.Weapons;

/** Server-side glue: applying classes, handing out loadouts, events. */
public final class MercServer {
	private MercServer() {
	}

	private static final Identifier HEALTH_MOD = Mercenaries.id("class_health");
	private static final Identifier SPEED_MOD = Mercenaries.id("class_speed");
	private static final Identifier KB_MOD = Mercenaries.id("class_knockback");
	private static final java.util.Map<java.util.UUID, Long> LAST_PAIN = new java.util.concurrent.ConcurrentHashMap<>();

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(MercPayloads.Select.TYPE, (payload, ctx) -> ctx.player().level().getServer().execute(() -> select(ctx.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(MercPayloads.Input.TYPE, (payload, ctx) -> ctx.player().level().getServer().execute(() ->
				WeaponHandler.setInput(ctx.player(), (payload.buttons() & 1) != 0, (payload.buttons() & 2) != 0)));
		ServerPlayNetworking.registerGlobalReceiver(MercPayloads.Action.TYPE, (payload, ctx) -> ctx.player().level().getServer().execute(() -> {
			ServerPlayer p = ctx.player();
			switch (payload.action()) {
				case MercPayloads.Action.RELOAD -> WeaponHandler.requestReload(p);
				case MercPayloads.Action.ABILITY -> WeaponHandler.toggleCloak(p);
				case MercPayloads.Action.DEMOLISH -> WeaponHandler.demolish(p);
				case MercPayloads.Action.MEDIC -> WeaponHandler.callMedic(p);
				default -> {
				}
			}
		}));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
			ServerPlayer p = handler.getPlayer();
			apply(p, false);
			if (MercConfig.get().promptOnFirstJoin && !Boolean.TRUE.equals(p.getAttached(MercData.PROMPTED))) {
				p.setAttached(MercData.PROMPTED, true);
				ServerPlayNetworking.send(p, new MercPayloads.OpenPicker(true));
			}
		}));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WeaponHandler.clear(handler.getPlayer()));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			MercData.setStatus(newPlayer, MercData.Status.DEFAULT);
			apply(newPlayer, true);
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Combat::allowDamage);

		// TF2 voice lines on pain / death (from the extracted pack; silent without it)
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (!(entity instanceof ServerPlayer p) || taken <= 0 || !p.isAlive()) return;
			MercData.Loadout l = MercData.loadout(p);
			if (!l.active()) return;
			long now = p.level().getGameTime();
			Long last = LAST_PAIN.get(p.getUUID());
			if (last != null && now - last < 12) return;
			LAST_PAIN.put(p.getUUID(), now);
			var ev = MercSounds.voice(l.mercClass(), "pain");
			if (ev != null) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), ev, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer p)) return;
			MercData.Loadout l = MercData.loadout(p);
			if (!l.active()) return;
			var ev = MercSounds.voice(l.mercClass(), "death");
			if (ev != null) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), ev, net.minecraft.sounds.SoundSource.PLAYERS, 1.2f, 1f);
		});

		// Merc weapons never leave as item entities (death drops, Q-drops); missing ones are re-issued.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemEntity ie && ie.getItem().getItem() instanceof MercWeaponItem) ie.discard();
		});

		ServerTickEvents.END_SERVER_TICK.register(MercServer::tick);
	}

	private static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			WeaponHandler.tick(p);
			if (server.getTickCount() % 20 == 0) {
				MercData.Loadout l = MercData.loadout(p);
				if (l.active() && p.isAlive()) giveMissing(p, l);
			}
		}
	}

	// ------------------------------------------------------------------ selection

	private static void select(ServerPlayer p, MercPayloads.Select sel) {
		MercClass c = MercClass.byOrdinal(sel.cls());
		String[] chosen = {sel.primary(), sel.secondary(), sel.melee()};
		WeaponDef.Slot[] slots = {WeaponDef.Slot.PRIMARY, WeaponDef.Slot.SECONDARY, WeaponDef.Slot.MELEE};
		for (int i = 0; i < 3; i++) {
			if (!Weapons.allowed(c, slots[i], Weapons.get(chosen[i]))) chosen[i] = Weapons.defaultFor(c, slots[i]);
		}
		MercData.Loadout l = c == MercClass.NONE ? MercData.Loadout.NONE
				: new MercData.Loadout(MercTeam.byOrdinal(sel.team()).ordinal(), c.ordinal(), chosen[0], chosen[1], chosen[2]);
		p.setAttached(MercData.LOADOUT, l);
		MercData.setStatus(p, MercData.Status.DEFAULT);
		apply(p, true);
		if (l.active()) {
			p.sendSystemMessage(Component.literal("You are now a ").append(Component.literal(l.mercTeam().label + " " + c.displayName()).withStyle(l.mercTeam().chat)));
		} else {
			p.sendSystemMessage(Component.literal("Back to plain Minecraft."));
		}
	}

	/** Applies class attributes, team and (optionally) a fresh loadout. */
	public static void apply(ServerPlayer p, boolean fullRefresh) {
		MercData.Loadout l = MercData.loadout(p);
		MercClass c = l.mercClass();

		setModifier(p.getAttribute(Attributes.MAX_HEALTH), HEALTH_MOD, c == MercClass.NONE ? 0 : maxHealth(l) - 20.0, AttributeModifier.Operation.ADD_VALUE);
		setModifier(p.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_MOD, c.speedMultiplier() - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		setModifier(p.getAttribute(Attributes.KNOCKBACK_RESISTANCE), KB_MOD, c.knockbackResistance, AttributeModifier.Operation.ADD_VALUE);
		if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
		if (fullRefresh) p.setHealth(p.getMaxHealth());

		joinTeam(p, l);
		removeMercItems(p, l, fullRefresh);
		if (l.active()) giveMissing(p, l);
	}

	private static double maxHealth(MercData.Loadout l) {
		double hp = l.mercClass().tf2Health;
		for (int i = 0; i < 3; i++) {
			WeaponDef d = Weapons.get(l.slot(i));
			if (d == null) continue;
			if (d.has(WeaponDef.Trait.EXTRA_HEALTH)) hp += 25;
			if (d.has(WeaponDef.Trait.LESS_HEALTH)) hp -= d.id().equals("sandman") ? 15 : 25;
		}
		return Math.round(hp / MercConfig.get().hpScale());
	}

	private static void setModifier(AttributeInstance attr, Identifier id, double amount, AttributeModifier.Operation op) {
		if (attr == null) return;
		attr.removeModifier(id);
		if (Math.abs(amount) > 1e-6) attr.addPermanentModifier(new AttributeModifier(id, amount, op));
	}

	private static void joinTeam(ServerPlayer p, MercData.Loadout l) {
		Scoreboard sb = p.level().getServer().getScoreboard();
		String name = p.getScoreboardName();
		PlayerTeam current = sb.getPlayersTeam(name);
		if (!l.active()) {
			if (current != null && current.getName().startsWith("merc_")) sb.removePlayerFromTeam(name, current);
			return;
		}
		MercTeam t = l.mercTeam();
		PlayerTeam team = sb.getPlayerTeam(t.scoreboardName());
		if (team == null) {
			team = sb.addPlayerTeam(t.scoreboardName());
			team.setDisplayName(Component.literal(t.label));
			team.setPlayerPrefix(Component.literal("[" + t.label + "] ").withStyle(t.chat));
			team.setAllowFriendlyFire(false);
			team.setSeeFriendlyInvisibles(true);
		}
		if (current != team) sb.addPlayerToTeam(name, team);
	}

	/** Removes merc weapons that don't belong to the current loadout (all of them on a full refresh). */
	private static void removeMercItems(ServerPlayer p, MercData.Loadout l, boolean all) {
		List<String> keep = all ? List.of() : wanted(l);
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() instanceof MercWeaponItem w && !keep.contains(w.def().id())) inv.setItem(i, ItemStack.EMPTY);
		}
	}

	private static List<String> wanted(MercData.Loadout l) {
		List<String> ids = new ArrayList<>();
		if (!l.active()) return ids;
		for (int i = 0; i < 3; i++) if (!l.slot(i).isEmpty()) ids.add(l.slot(i));
		if (l.mercClass() == MercClass.ENGINEER) ids.add("construction_pda");
		return ids;
	}

	/**
	 * Puts any missing loadout weapons in the hotbar (slots 1-4). If a slot holds a normal item, that item
	 * is moved to a free inventory slot first - nothing the player owns is ever deleted.
	 */
	private static void giveMissing(ServerPlayer p, MercData.Loadout l) {
		Inventory inv = p.getInventory();
		List<String> want = wanted(l);
		for (int idx = 0; idx < want.size(); idx++) {
			String id = want.get(idx);
			boolean has = false;
			for (int i = 0; i < inv.getContainerSize() && !has; i++) {
				has = inv.getItem(i).getItem() instanceof MercWeaponItem w && w.def().id().equals(id);
			}
			if (has) continue;
			MercWeaponItem item = Weapons.item(id);
			if (item == null) continue;
			ItemStack stack = new ItemStack(item);
			item.refill(stack);
			// team colour for the extracted resource pack's red/blu skins
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(l.mercTeam() == MercTeam.BLU ? "blu" : "red"), List.of()));

			int slot = idx; // hotbar 0..3
			ItemStack occupant = inv.getItem(slot);
			if (!occupant.isEmpty()) {
				int free = freeMainSlot(inv);
				if (free >= 0) {
					inv.setItem(free, occupant);
					inv.setItem(slot, ItemStack.EMPTY);
				} else {
					slot = -1;
				}
			}
			if (slot >= 0 && inv.getItem(slot).isEmpty()) inv.setItem(slot, stack);
			else if (!inv.add(stack)) p.sendSystemMessage(Component.literal("Inventory full: couldn't give " + item.def().name()));
		}
	}

	private static int freeMainSlot(Inventory inv) {
		for (int i = 9; i < 36; i++) if (inv.getItem(i).isEmpty()) return i;
		for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
		return -1;
	}
}
