package de.behindtheblocks.cinematic.content;

import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Items of the Nether Core content set. Blocks register their own block items in {@code BtbBlocks}. */
public final class BtbItems {
	static final List<Item> TAB_ITEMS = new ArrayList<>();

	public static final HeartOfTheNetherItem HEART_OF_THE_NETHER = register("heart_of_the_nether",
			HeartOfTheNetherItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());
	public static final Item NETHER_KEY = register("nether_key", Item::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE).fireResistant());
	public static final Item NETHER_CRYSTAL = register("nether_crystal", Item::new,
			new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON).fireResistant());
	/** Das alte Buch: right-click shows the message page with the ink effect. */
	public static final HeartBookItem HEART_BOOK = register("heart_book", HeartBookItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE).fireResistant());
	/** Regieklappe: clap + take counter; sneak = reset the set for the next take. */
	public static final ClapperboardItem CLAPPERBOARD = register("clapperboard", ClapperboardItem::new,
			new Item.Properties().stacksTo(1));
	/** Glutfackel: burning flare with real light, red smoke and embers for 45 s where you look. */
	public static final EmberFlareItem EMBER_FLARE = register("ember_flare", EmberFlareItem::new,
			new Item.Properties().stacksTo(16).fireResistant());

	private BtbItems() {
	}

	static <T extends Item> T register(String name, Function<Item.Properties, T> factory, Item.Properties properties) {
		Identifier id = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, name);
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
		T item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
		TAB_ITEMS.add(item);
		return item;
	}

	public static void init() {
		EmberFlareItem.init();
		CreativeModeTab tab = FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.btb_cinematic"))
				.icon(() -> new ItemStack(HEART_OF_THE_NETHER))
				.displayItems((params, output) -> TAB_ITEMS.forEach(output::accept))
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "nether_core"), tab);
	}
}
