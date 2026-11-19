package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.camera.CinematicCameraController;
import de.behindtheblocks.cinematic.client.effects.ScreenEffectRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame render option overrides: the blur radius used by the blur pass (driven by the BLUR
 * screen effect) and view bobbing (disabled during camera shots so the camera never wobbles).
 * Target: {@code GameRenderer.extractOptions()} (26.3).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** Adds the cinematic post shaders (heat shimmer, film grain) to this frame's post effects. */
	/** Film export: render at the export size (an integer multiple of the window, GUI scaled along). */
	@Inject(method = "extractWindow", at = @At("TAIL"))
	private void btb$exportSize(CallbackInfo ci) {
		if (de.behindtheblocks.cinematic.client.export.ExportManager.active()) {
			var w = ((GameRenderer) (Object) this).gameRenderState().windowRenderState;
			w.width = de.behindtheblocks.cinematic.client.export.ExportManager.renderWidth(w.width);
			w.height = de.behindtheblocks.cinematic.client.export.ExportManager.renderHeight(w.height);
			w.guiScale = de.behindtheblocks.cinematic.client.export.ExportManager.guiScale();
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void btb$exportFrame(CallbackInfo ci) {
		de.behindtheblocks.cinematic.client.export.ExportManager.afterFrame();
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void btb$postEffects(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		ScreenEffectRenderer.addPostEffects(((GameRenderer) (Object) this).getRequestedPostEffects());
	}

	@Inject(method = "extractOptions", at = @At("TAIL"))
	private void btb$overrideOptions(CallbackInfo ci) {
		OptionsRenderState state = ((GameRenderer) (Object) this).gameRenderState().optionsRenderState;
		int blur = ScreenEffectRenderer.blurRadius();
		if (blur > 0) {
			state.menuBackgroundBlurriness = blur;
		}
		if (CinematicCameraController.isActive()) {
			state.bobView = false;
		}
	}
}
