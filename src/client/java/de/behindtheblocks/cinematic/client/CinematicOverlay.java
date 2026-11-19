package de.behindtheblocks.cinematic.client;

import de.behindtheblocks.cinematic.client.effects.ScreenEffectRenderer;
import de.behindtheblocks.cinematic.client.hud.CinematicHudRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Composes the cinematic overlay in a fixed layer order:
 * blur → tint → vignette → letterbox → texts (below effects) → fade/transition → texts (above
 * effects, the default — so title cards stay readable on a black or white screen) → flashes.
 */
public final class CinematicOverlay {
	private CinematicOverlay() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
		ScreenEffectRenderer.renderBase(g, partial);
		de.behindtheblocks.cinematic.client.director.PipOverlay.render(g);
		CinematicHudRenderer.render(g, partial, false);
		ScreenEffectRenderer.renderCover(g, partial);
		CinematicHudRenderer.render(g, partial, true);
		ScreenEffectRenderer.renderFlashes(g, partial);
		de.behindtheblocks.cinematic.client.director.DirectorHotkeys.renderMonitor(g);
		de.behindtheblocks.cinematic.client.director.StudioClient.render(g);
		de.behindtheblocks.cinematic.client.replay.ReplayEditor.renderHud(g, partial);
	}
}
