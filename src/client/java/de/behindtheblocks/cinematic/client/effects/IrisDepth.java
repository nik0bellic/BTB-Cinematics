package de.behindtheblocks.cinematic.client.effects;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import de.behindtheblocks.cinematic.BtbCinematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Real depth of field with a shader pack: Iris keeps the depth buffer in its own textures, so the
 * vanilla depth is empty. This texture always points at Iris' current scene depth (looked up by
 * reflection, no compile-time dependency), and the "dofi" post effects read it – things far away
 * really go soft, not just the screen edges. Without Iris (or if Iris changes) it reports "not ready"
 * and the screen-space fallback is used.
 */
public final class IrisDepth extends AbstractTexture {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/irisdepth");
	public static final Identifier ID = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "textures/effect/iris_depth.png");
	private static IrisDepth instance;
	private static boolean broken;
	/** Which Iris depth texture to read (test switch: -Dbtb.irisdepth=noHand,…). */
	private static final String[] ORDER = System.getProperty("btb.irisdepth", "noHand,noTranslucents,currentDepthTexture").split(",");
	private static String logged = "";

	private GpuTexture source;
	private GpuTextureView view;

	private IrisDepth() {
	}

	public static void init() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			instance = new IrisDepth();
			mc.getTextureManager().register(ID, instance);
		});
	}

	/** Points the texture at Iris' depth of this frame; false if there is none (no shader pack, other Iris version). */
	public static boolean refresh() {
		if (instance == null || broken || ORDER[0].isEmpty()) {
			return false;
		}
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			Object manager = iris.getMethod("getPipelineManager").invoke(null);
			Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
			if (pipeline == null) {
				return false;
			}
			Field rt = find(pipeline.getClass(), "renderTargets");
			if (rt == null) {
				return false;
			}
			Object targets = rt.get(pipeline);
			GpuTexture depth = null;
			for (String name : ORDER) {
				Field f = find(targets.getClass(), name);
				Object v = f == null ? null : f.get(targets);
				if (v instanceof GpuTexture t && !t.isClosed() && (t.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
					depth = t;
					String info = name + " " + t.getLabel() + " " + t.getFormat() + " " + t.getWidth(0) + "x" + t.getHeight(0);
					if (!info.equals(logged)) {
						logged = info;
						LOG.info("Depth of field reads Iris depth: {}", info);
					}
					break;
				}
			}
			if (depth == null) {
				return false;
			}
			if (depth != instance.source || instance.view == null) {
				if (instance.view != null) {
					instance.view.close();
				}
				instance.source = depth;
				instance.view = RenderSystem.getDevice().createTextureView(depth);
			}
			return true;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			broken = true; // never try again this session, the fallback stays
			LOG.warn("Iris depth not available ({}), depth of field falls back to screen space", e.toString());
			return false;
		}
	}

	private static Field find(Class<?> c, String name) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
				// look in the super class
			}
		}
		return null;
	}

	@Override
	public GpuTextureView getTextureView() {
		if (view == null) {
			// before the first shader frame: the main depth (never null for the post pass)
			return Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
		}
		return view;
	}

	@Override
	public GpuTexture getTexture() {
		return source != null ? source : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture();
	}

	@Override
	public GpuSampler getSampler() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
	}

	@Override
	public void close() {
		if (view != null) {
			view.close(); // only our view – the texture itself belongs to Iris
			view = null;
		}
	}
}
