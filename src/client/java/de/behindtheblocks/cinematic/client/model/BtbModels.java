package de.behindtheblocks.cinematic.client.model;

import com.geckolib.animatable.client.GeoRenderProvider;
import com.geckolib.model.DefaultedItemGeoModel;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.content.HeartOfTheNetherItem;
import net.minecraft.resources.Identifier;

/** Client-side model wiring for GeckoLib-animated items. */
public final class BtbModels {
	private BtbModels() {
	}

	private static <T extends net.minecraft.world.entity.Entity & com.geckolib.animatable.GeoAnimatable> void glowing(
			net.minecraft.world.entity.EntityType<T> type) {
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(type, ctx -> {
			var r = new com.geckolib.renderer.GeoEntityRenderer<>(ctx, type);
			r.withRenderLayer(l -> new AutoGlowingGeoLayer<>(l) {
				@Override
				public void submitRenderTask(com.geckolib.renderer.base.RenderPassInfo<net.minecraft.client.renderer.entity.state.EntityRenderState> info,
						net.minecraft.client.renderer.SubmitNodeCollector collector) {
					if (!ShaderCompat.shaderPackInUse()) {
						super.submitRenderTask(info, collector);
					}
				}
			});
			return r;
		});
	}

	public static void init() {
		glowing(de.behindtheblocks.cinematic.content.entity.BtbEntities.GLUTLING);
		glowing(de.behindtheblocks.cinematic.content.entity.BtbEntities.ASCHEKRIECHER);
		glowing(de.behindtheblocks.cinematic.content.entity.BtbEntities.SEELENWISP);
		net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(de.behindtheblocks.cinematic.content.BtbBlocks.PORTAL_FOCUS_ENTITY,
				ctx -> new com.geckolib.renderer.GeoBlockRenderer<>(ctx, de.behindtheblocks.cinematic.content.BtbBlocks.PORTAL_FOCUS_ENTITY));
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
				de.behindtheblocks.cinematic.content.entity.BtbEntities.CRASH_GHAST, net.minecraft.client.renderer.entity.GhastRenderer::new);
		// The Heart Warden: own GeckoLib model (geckolib/models/entity/herzwaechter.geo.json) with glowing heart.
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
				de.behindtheblocks.cinematic.content.entity.BtbEntities.HEART_WARDEN, ctx -> {
					var r = new com.geckolib.renderer.GeoEntityRenderer<>(ctx, de.behindtheblocks.cinematic.content.entity.BtbEntities.HEART_WARDEN);
					r.withRenderLayer(l -> new AutoGlowingGeoLayer<>(l) {
						@Override
						public void submitRenderTask(com.geckolib.renderer.base.RenderPassInfo<net.minecraft.client.renderer.entity.state.EntityRenderState> info,
								net.minecraft.client.renderer.SubmitNodeCollector collector) {
							if (!ShaderCompat.shaderPackInUse()) {
								super.submitRenderTask(info, collector);
							}
						}
					});
					return r;
				});
		HeartOfTheNetherItem.renderProviderFactory = () -> new GeoRenderProvider() {
			private GeoItemRenderer<HeartOfTheNetherItem> renderer;

			@Override
			public GeoItemRenderer<?> getGeoItemRenderer() {
				if (renderer == null) {
					renderer = new GeoItemRenderer<HeartOfTheNetherItem>(
							new DefaultedItemGeoModel<>(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "heart_of_the_nether")));
					// heart_of_the_nether_glowmask.png: full-bright molten core and crystal edges.
					// Not with an Iris shader pack: the emissive pass produces artifacts there (shaders light it anyway).
					renderer.withRenderLayer(r -> new AutoGlowingGeoLayer<>(r) {
						@Override
						public void submitRenderTask(com.geckolib.renderer.base.RenderPassInfo<com.geckolib.renderer.base.GeoRenderState> info,
								net.minecraft.client.renderer.SubmitNodeCollector collector) {
							if (!ShaderCompat.shaderPackInUse()) {
								super.submitRenderTask(info, collector);
							}
						}
					});
				}
				return renderer;
			}
		};
	}
}
