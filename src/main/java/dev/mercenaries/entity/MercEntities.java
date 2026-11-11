package dev.mercenaries.entity;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import dev.mercenaries.Mercenaries;

public final class MercEntities {
	private MercEntities() {
	}

	public static final ResourceKey<EntityType<?>> PROJECTILE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Mercenaries.id("projectile"));
	public static final ResourceKey<EntityType<?>> BUILDING_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Mercenaries.id("building"));

	public static final EntityType<MercProjectile> PROJECTILE = EntityType.Builder.<MercProjectile>of(MercProjectile::new, MobCategory.MISC)
			.sized(0.3f, 0.3f)
			.clientTrackingRange(8)
			.updateInterval(1)
			.build(PROJECTILE_KEY);

	public static final EntityType<BuildingEntity> BUILDING = EntityType.Builder.<BuildingEntity>of(BuildingEntity::new, MobCategory.MISC)
			.sized(0.9f, 1.1f)
			.clientTrackingRange(10)
			.build(BUILDING_KEY);

	private static final Map<MercProjectile.Type, Item> PROJECTILE_ITEMS = new EnumMap<>(MercProjectile.Type.class);

	public static Item projectileItem(MercProjectile.Type t) {
		return PROJECTILE_ITEMS.get(t);
	}

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, PROJECTILE_KEY, PROJECTILE);
		Registry.register(BuiltInRegistries.ENTITY_TYPE, BUILDING_KEY, BUILDING);
		FabricDefaultAttributeRegistry.register(BUILDING, BuildingEntity.createAttributes());

		// Hidden items used only to render projectiles (ThrownItemRenderer draws the item model).
		for (MercProjectile.Type t : MercProjectile.Type.values()) {
			String name = "projectile_" + switch (t) {
				case ROCKET -> "rocket";
				case PIPE -> "pipe";
				case STICKY -> "sticky";
				case FLARE -> "flare";
				case ARROW -> "arrow";
				case JAR -> "jar";
				case SYRINGE -> "syringe";
			};
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Mercenaries.id(name.toLowerCase(Locale.ROOT)));
			PROJECTILE_ITEMS.put(t, Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key))));
		}
	}
}
