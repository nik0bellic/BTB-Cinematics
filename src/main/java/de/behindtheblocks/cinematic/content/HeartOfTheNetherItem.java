package de.behindtheblocks.cinematic.content;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/**
 * The Heart of the Nether: an animated GeckoLib model (pulsing molten core, orbiting crystal
 * shards, rotating golden ring) with an emissive glow layer. Model, animation and textures live in
 * {@code assets/btb_cinematic/geckolib/...} and {@code textures/item/heart_of_the_nether*.png}
 * and can be replaced with a Blockbench export of the same names.
 */
public final class HeartOfTheNetherItem extends Item implements GeoItem {
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

	/**
	 * Installed by the client initializer; never touched on a dedicated server. Typed as Object so
	 * this common class does not reference client-only GeckoLib renderer types.
	 */
	public static Supplier<Object> renderProviderFactory;
	private Object renderProvider;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public HeartOfTheNetherItem(Properties properties) {
		super(properties);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>("main", 0, test -> test.setAndContinue(IDLE)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}

	@Override
	public Object getRenderProvider() {
		if (renderProvider == null && renderProviderFactory != null) {
			renderProvider = renderProviderFactory.get();
		}
		return renderProvider;
	}
}
