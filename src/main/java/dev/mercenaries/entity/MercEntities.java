package dev.mercenaries.entity;


import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

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

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, PROJECTILE_KEY, PROJECTILE);
		Registry.register(BuiltInRegistries.ENTITY_TYPE, BUILDING_KEY, BUILDING);
		FabricDefaultAttributeRegistry.register(BUILDING, BuildingEntity.createAttributes());

	}
}
