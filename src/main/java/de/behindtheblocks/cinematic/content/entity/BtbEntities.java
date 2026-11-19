package de.behindtheblocks.cinematic.content.entity;

import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Custom creatures of the mod. */
public final class BtbEntities {
	public static final ResourceKey<EntityType<?>> HEART_WARDEN_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "herzwaechter"));
	public static final EntityType<HeartWarden> HEART_WARDEN = Registry.register(BuiltInRegistries.ENTITY_TYPE, HEART_WARDEN_KEY,
			EntityType.Builder.of(HeartWarden::new, MobCategory.MONSTER).fireImmune().sized(1.4f, 3.6f).eyeHeight(3.1f)
					.clientTrackingRange(10).build(HEART_WARDEN_KEY));

	/** A ghast for the crash shot: looks like the real one, but also exists on "Peaceful" (it is scenery, not a monster). */
	public static final ResourceKey<EntityType<?>> CRASH_GHAST_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "crash_ghast"));
	public static final EntityType<net.minecraft.world.entity.monster.Ghast> CRASH_GHAST = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			CRASH_GHAST_KEY, EntityType.Builder.<net.minecraft.world.entity.monster.Ghast>of(net.minecraft.world.entity.monster.Ghast::new,
					MobCategory.MISC).fireImmune().sized(4f, 4f).eyeHeight(2.6f).clientTrackingRange(16).build(CRASH_GHAST_KEY));

	private static <T extends net.minecraft.world.entity.Entity> EntityType<T> mob(String name, EntityType.EntityFactory<T> f, MobCategory cat,
			float w, float h) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.of(f, cat).fireImmune().sized(w, h).clientTrackingRange(10).build(key));
	}

	/** Small creatures for the film. */
	public static final EntityType<Glutling> GLUTLING = mob("glutling", Glutling::new, MobCategory.MONSTER, 0.5f, 0.7f);
	public static final EntityType<Aschekriecher> ASCHEKRIECHER = mob("aschekriecher", Aschekriecher::new, MobCategory.MONSTER, 0.9f, 0.5f);
	public static final EntityType<Seelenwisp> SEELENWISP = mob("seelenwisp", Seelenwisp::new, MobCategory.AMBIENT, 0.4f, 0.5f);

	private BtbEntities() {
	}

	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> HeartWarden.tickFloatingKeys());
		FabricDefaultAttributeRegistry.register(HEART_WARDEN, HeartWarden.createAttributes());
		FabricDefaultAttributeRegistry.register(GLUTLING, net.minecraft.world.entity.monster.Vex.createAttributes());
		FabricDefaultAttributeRegistry.register(ASCHEKRIECHER, net.minecraft.world.entity.monster.spider.Spider.createAttributes());
		FabricDefaultAttributeRegistry.register(SEELENWISP, net.minecraft.world.entity.ambient.Bat.createAttributes());
		FabricDefaultAttributeRegistry.register(CRASH_GHAST, net.minecraft.world.entity.monster.Ghast.createAttributes());
	}
}
