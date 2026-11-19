package de.behindtheblocks.cinematic.client;

import de.behindtheblocks.cinematic.client.camera.CinematicCameraController;
import de.behindtheblocks.cinematic.client.director.DirectorHotkeys;
import de.behindtheblocks.cinematic.client.director.DirectorScreen;
import de.behindtheblocks.cinematic.client.effects.ScreenEffectRenderer;
import de.behindtheblocks.cinematic.client.hud.CinematicHudRenderer;
import de.behindtheblocks.cinematic.client.model.BtbModels;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.client.sound.CinematicSoundPlayer;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Client entry point. Only presentation lives here: everything that changes the world runs on
 * the server and reaches the client as cues.
 */
public final class BtbCinematicClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BtbModels.init();
		ClientCueDispatcher.init();
		ClientSceneState.init();
		CinematicCameraController.init();
		ScreenEffectRenderer.init();
		de.behindtheblocks.cinematic.client.director.CrewMarkers.init();
		de.behindtheblocks.cinematic.client.director.PipOverlay.init();
		de.behindtheblocks.cinematic.client.export.ExportManager.init();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(mc -> de.behindtheblocks.cinematic.client.effects.LutManager.init());
		CinematicHudRenderer.init();
		CinematicSoundPlayer.init();
		DirectorScreen.registerCues();
		DirectorHotkeys.init();
		de.behindtheblocks.cinematic.client.replay.ReplayEditor.init();
		de.behindtheblocks.cinematic.client.obs.ObsBridge.init();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ClientTimeline.tick(mc);
			CinematicCameraController.tick();
			ScreenEffectRenderer.tick();
			de.behindtheblocks.cinematic.client.director.CrewMarkers.tick();
			CinematicHudRenderer.tick();
			CinematicSoundPlayer.tick();
			DirectorHotkeys.tick(mc);
			de.behindtheblocks.cinematic.client.replay.ReplayEditor.tick(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			ClientSceneState.clear();
			CinematicCameraController.reset();
			ScreenEffectRenderer.clearAll();
			CinematicHudRenderer.clearAll();
			CinematicSoundPlayer.reset();
		}));
	}
}
