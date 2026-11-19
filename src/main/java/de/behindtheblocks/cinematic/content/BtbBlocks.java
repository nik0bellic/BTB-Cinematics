package de.behindtheblocks.cinematic.content;

import de.behindtheblocks.cinematic.BtbCinematic;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

/** Blocks of the Nether Core content set (portal construction). */
public final class BtbBlocks {
	public static final PortalFrameBlock PORTAL_FRAME = register("portal_frame", PortalFrameBlock::new,
			BlockBehaviour.Properties.of().strength(50f, 1200f).sound(SoundType.GILDED_BLACKSTONE)
					.lightLevel(s -> s.getValue(PortalFrameBlock.LIT) ? 10 : 0), true);
	/** Quartz-and-gold frame of the white portal; ice-blue runes glow while charging. Reuses the LIT logic. */
	public static final PortalFrameBlock RADIANT_FRAME = register("radiant_frame", PortalFrameBlock::new,
			BlockBehaviour.Properties.of().strength(50f, 1200f).sound(SoundType.AMETHYST)
					// Always glowing: full light and a fullbright texture, even at night.
					.lightLevel(s -> s.getValue(PortalFrameBlock.LIT) ? 15 : 13).emissiveRendering(s -> true), true);
	public static final PortalKeystoneBlock PORTAL_KEYSTONE = register("portal_keystone", PortalKeystoneBlock::new,
			BlockBehaviour.Properties.of().strength(50f, 1200f).sound(SoundType.GILDED_BLACKSTONE)
					.lightLevel(s -> s.getValue(PortalKeystoneBlock.STAGE).light), true);
	public static final RiftPortalBlock RIFT_PORTAL = register("rift_portal", RiftPortalBlock::new,
			BlockBehaviour.Properties.of().strength(-1f, 3600000f).noCollision().noOcclusion().noLootTable()
					.sound(SoundType.GLASS).lightLevel(s -> s.getValue(RiftPortalBlock.STYLE) == PortalStyle.WHITE ? 15 : 12), false);

	/** Dark crimson bricks with slowly pulsing molten cracks (animated texture). */
	public static final Block HEARTSTONE_BRICKS = register("heartstone_bricks", Block::new,
			BlockBehaviour.Properties.of().strength(2f, 6f).sound(SoundType.NETHER_BRICKS).lightLevel(s -> 3), true);
	/** Glowing vein that pulses like a heartbeat; runs up the Heart Tower. */
	public static final Block NETHER_VEIN = register("nether_vein", Block::new,
			BlockBehaviour.Properties.of().strength(1.5f, 6f).sound(SoundType.SHROOMLIGHT).lightLevel(s -> 10), true);
	/** Pedestal for the Heart of the Nether (own 3D model, glowing top). */
	public static final Block HEART_ALTAR = register("heart_altar", Block::new,
			BlockBehaviour.Properties.of().strength(50f, 1200f).sound(SoundType.GILDED_BLACKSTONE).noOcclusion().lightLevel(s -> 8), true);

	// ------------------------------------------------------------------ atmosphere (lamps, light, fog)

	/** Glutkessel: a blackstone fire bowl on three legs. */
	public static final AtmosphereBlock BRAZIER = register("brazier", p -> new AtmosphereBlock(p, AtmosphereBlock.Fx.BRAZIER,
			Block.box(2, 0, 2, 14, 13, 14), true), BlockBehaviour.Properties.of().strength(3f, 6f).sound(SoundType.NETHER_BRICKS)
			.noOcclusion().lightLevel(s -> AtmosphereBlock.light(s, 15)), true);
	/** Kettenleuchter: an iron ring with six candles, hangs from the ceiling. */
	public static final AtmosphereBlock CHANDELIER = register("chandelier", p -> new AtmosphereBlock(p, AtmosphereBlock.Fx.CHANDELIER,
			Block.box(2, 6, 2, 14, 16, 14), false), BlockBehaviour.Properties.of().strength(1f, 3f).sound(SoundType.CHAIN)
			.noOcclusion().lightLevel(s -> AtmosphereBlock.light(s, 15)), true);
	/** Lichtsäule: a soft beam of light (stack them), dust drifting through it. Walk-through. */
	public static final AtmosphereBlock LIGHT_SHAFT = register("light_shaft", p -> new AtmosphereBlock(p, AtmosphereBlock.Fx.SHAFT,
			Block.box(3, 0, 3, 13, 16, 13), false), BlockBehaviour.Properties.of().strength(0.2f).sound(SoundType.AMETHYST)
			.noOcclusion().noLootTable().lightLevel(s -> AtmosphereBlock.light(s, 11)).emissiveRendering(s -> s.getValue(AtmosphereBlock.POWER) > 0), true);
	/** Glutriss: blackstone floor with glowing cracks, embers rising. */
	public static final AtmosphereBlock EMBER_CRACK = register("ember_crack", p -> new AtmosphereBlock(p, AtmosphereBlock.Fx.EMBERS,
			Block.box(0, 0, 0, 16, 16, 16), true), BlockBehaviour.Properties.of().strength(1.5f, 6f).sound(SoundType.GILDED_BLACKSTONE)
			.lightLevel(s -> AtmosphereBlock.light(s, 7)), true);
	/** Nebelquelle: a small vent in the floor; low ground fog creeps out of it. */
	public static final AtmosphereBlock FOG_VENT = register("fog_vent", p -> new AtmosphereBlock(p, AtmosphereBlock.Fx.FOG,
			Block.box(0, 0, 0, 16, 2, 16), true), BlockBehaviour.Properties.of().strength(1.5f, 6f).sound(SoundType.BASALT)
			.noOcclusion(), true);
	/** Seelenkristall-Leuchte: a cluster of cold blue crystals. */
	public static final AtmosphereBlock SOUL_CRYSTAL_LAMP = register("soul_crystal_lamp", p -> new AtmosphereBlock(p, AtmosphereBlock.Fx.CRYSTAL,
			Block.box(3, 0, 3, 13, 14, 13), true), BlockBehaviour.Properties.of().strength(1f, 3f).sound(SoundType.AMETHYST_CLUSTER)
			.noOcclusion().lightLevel(s -> AtmosphereBlock.light(s, 12)), true);

	// ------------------------------------------------------------------ portal hall

	/** Portalfokus: floating crystal with spinning rings (animated model). */
	public static final PortalFocusBlock PORTAL_FOCUS = register("portal_focus", PortalFocusBlock::new,
			BlockBehaviour.Properties.of().strength(3f, 9f).sound(SoundType.AMETHYST).noOcclusion().lightLevel(s -> 13), true);
	public static final net.minecraft.world.level.block.entity.BlockEntityType<PortalFocusBlock.Entity> PORTAL_FOCUS_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "portal_focus"),
			new net.minecraft.world.level.block.entity.BlockEntityType<>(PortalFocusBlock.Entity::new, java.util.Set.of(PORTAL_FOCUS)));
	/** Runensäule: blackstone pillar with glowing white runes (stack them). */
	public static final Block RUNE_PILLAR = register("rune_pillar", Block::new,
			BlockBehaviour.Properties.of().strength(3f, 9f).sound(SoundType.POLISHED_DEEPSLATE).lightLevel(s -> 6), true);
	/** Glyphenplatte: floor tile with a glowing glyph circle. */
	public static final Block GLYPH_PLATE = register("glyph_plate", Block::new,
			BlockBehaviour.Properties.of().strength(3f, 9f).sound(SoundType.POLISHED_DEEPSLATE).lightLevel(s -> 5), true);

	private BtbBlocks() {
	}

	private static <T extends Block> T register(String name, Function<BlockBehaviour.Properties, T> factory,
			BlockBehaviour.Properties props, boolean withItem) {
		Identifier id = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, name);
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		T block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
		if (withItem) {
			ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
			Item item = Registry.register(BuiltInRegistries.ITEM, itemKey,
					new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
			BtbItems.TAB_ITEMS.add(item);
		}
		return block;
	}

	public static void init() {
	}
}
