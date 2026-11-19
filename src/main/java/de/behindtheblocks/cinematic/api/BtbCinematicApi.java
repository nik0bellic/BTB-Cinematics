package de.behindtheblocks.cinematic.api;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.bus.BusMessage;
import de.behindtheblocks.cinematic.destruction.DestructionManager;
import de.behindtheblocks.cinematic.effects.EffectManager;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.event.CinematicEventType;
import de.behindtheblocks.cinematic.hud.HudManager;
import de.behindtheblocks.cinematic.hud.HudTextSpec;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.portal.PortalManager;
import de.behindtheblocks.cinematic.scene.SceneManager;
import de.behindtheblocks.cinematic.showcase.ShowcaseManager;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Stable, server-side facade for other mods. All methods must be called on the server thread.
 * {@code players == null} always means "everyone".
 *
 * <p>Client side: register handlers for your own cue types with
 * {@code de.behindtheblocks.cinematic.client.api.BtbCinematicClientApi#registerCue}, then send them
 * with {@link #sendClientCue} or from a scene file with the {@code client_cue} event.
 */
public final class BtbCinematicApi {
	private final CinematicEngine engine;

	public BtbCinematicApi(CinematicEngine engine) {
		this.engine = engine;
	}

	/** Adds a new timeline event type usable in scene JSON (ids should be prefixed, e.g. {@code mymod_rain}). */
	public void registerEventType(CinematicEventType type) {
		engine.events().register(type);
	}

	/** Listen to scene lifecycle messages ({@code SceneMessages.Started}, {@code Reset}, ...). */
	public <T extends BusMessage> void subscribe(Class<T> type, Consumer<? super T> listener) {
		engine.bus().subscribe(type, listener);
	}

	// scenes
	public SceneManager.Outcome startScene(String id) {
		return engine.scenes().restart(id);
	}

	public SceneManager.Outcome stopScene(String id) {
		return engine.scenes().stop(id);
	}

	public SceneManager.Outcome resetScene(String id) {
		return engine.scenes().reset(id);
	}

	// presentation
	/** Shows a HUD overlay; {@code spec} uses the same JSON as {@code config/btb_cinematic/hud/*.json}. */
	public void showHud(JsonObject spec, Collection<ServerPlayer> players) {
		HudTextSpec.fromJson(new JsonParams(spec).getString("id", "api"), spec); // validate
		engine.module(HudManager.class).orElseThrow().show(TimelineManager.MANUAL, 0, spec, players);
	}

	public void hideHud(String id, int fadeTicks, Collection<ServerPlayer> players) {
		engine.module(HudManager.class).orElseThrow().hide(TimelineManager.MANUAL, 0, id, fadeTicks, players);
	}

	public void screenEffect(EffectSpec effect, Collection<ServerPlayer> players) {
		engine.module(EffectManager.class).orElseThrow().send(TimelineManager.MANUAL, 0, effect, players);
	}

	public void cameraShake(float intensity, int ticks, Collection<ServerPlayer> players) {
		engine.module(CameraManager.class).orElseThrow().shake(TimelineManager.MANUAL, 0, intensity, ticks, 1f, players);
	}

	/** Sends a custom cue that a client-side handler (yours) renders. */
	public void sendClientCue(String type, JsonObject data, Collection<ServerPlayer> players) {
		engine.cues().queue(TimelineManager.MANUAL, 0, type, data, players);
	}

	// set pieces
	public String playShowcase(String id) {
		return engine.module(ShowcaseManager.class).orElseThrow().play(id, null, TimelineManager.MANUAL);
	}

	public String activatePortal(String id, boolean instant) {
		return engine.module(PortalManager.class).orElseThrow().activate(id, instant, TimelineManager.MANUAL);
	}

	public String triggerDestruction(String id) {
		return engine.module(DestructionManager.class).orElseThrow().trigger(id, null, TimelineManager.MANUAL);
	}

	/** Direct access for advanced integrations. */
	public CinematicEngine engine() {
		return engine;
	}
}
