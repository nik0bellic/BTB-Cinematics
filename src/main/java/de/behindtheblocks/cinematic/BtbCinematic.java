package de.behindtheblocks.cinematic;

import de.behindtheblocks.cinematic.ambience.AmbienceManager;
import de.behindtheblocks.cinematic.api.BtbCinematicApi;
import de.behindtheblocks.cinematic.api.BtbCinematicPlugin;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.content.BtbBlocks;
import de.behindtheblocks.cinematic.content.BtbItems;
import de.behindtheblocks.cinematic.destruction.DestructionManager;
import de.behindtheblocks.cinematic.director.DirectorManager;
import de.behindtheblocks.cinematic.portal.PortalManager;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.effects.EffectManager;
import de.behindtheblocks.cinematic.hud.HudManager;
import de.behindtheblocks.cinematic.showcase.ShowcaseManager;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.sound.SoundManager;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.network.BtbNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both sides) entry point of "Behind The Blocks – Cinematic Engine".
 *
 * <p>Everything here runs on the dedicated server and on the integrated server of a client.
 * Client-only presentation code lives in the {@code client} source set.
 */
public final class BtbCinematic implements ModInitializer {
	/** {@code client_cue}: forwards arbitrary data to a client handler registered by another mod. */
	private static void registerClientCueEvent() {
		ENGINE.events().register(BuiltinEvents.simple("client_cue",
				"Forwards a custom cue to client handlers of other mods. {cue (namespace:name), data? {...}, players?}",
				p -> {
					String cue = p.requireString("cue");
					if (!cue.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || cue.length() > 64) {
						throw new de.behindtheblocks.cinematic.json.JsonParamException("'cue' must be namespace:name");
					}
					com.google.gson.JsonObject data = p.getObject("data").orElse(new com.google.gson.JsonObject());
					de.behindtheblocks.cinematic.event.builtin.PlayerTargets t = de.behindtheblocks.cinematic.event.builtin.PlayerTargets.parse(p);
					return ctx -> ctx.engine().cues().queue(ctx.sceneId(), ctx.tick(), cue, data, t.all() ? null : t.resolve(ctx.server()));
				}));
	}

	public static final String MOD_ID = "btb_cinematic";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static final CinematicEngine ENGINE = new CinematicEngine();

	public static CinematicEngine engine() {
		return ENGINE;
	}

	@Override
	public void onInitialize() {
		de.behindtheblocks.cinematic.util.I18n.loadSettings();
		BtbNetworking.registerPayloadTypes();
		BtbSounds.init();
		BtbItems.init();
		de.behindtheblocks.cinematic.content.entity.BtbEntities.init();
		BtbBlocks.init();
		BuiltinEvents.register(ENGINE.events());
		ENGINE.addModule(new TimelineManager());
		ENGINE.addModule(new CameraManager());
		ENGINE.addModule(new EffectManager());
		ENGINE.addModule(new HudManager());
		ENGINE.addModule(new SoundManager());
		ENGINE.addModule(new ShowcaseManager());
		ENGINE.addModule(new PortalManager());
		ENGINE.addModule(new DestructionManager());
		ENGINE.addModule(new AmbienceManager());
		ENGINE.addModule(new de.behindtheblocks.cinematic.parkour.ParkourManager());
		ENGINE.addModule(new de.behindtheblocks.cinematic.story.TriggerManager());
		ENGINE.addModule(new de.behindtheblocks.cinematic.story.SpawnManager());
		ENGINE.addModule(new de.behindtheblocks.cinematic.scene.SceneEditor());
		ENGINE.addModule(new de.behindtheblocks.cinematic.director.WorldFxManager());
		ENGINE.addModule(new de.behindtheblocks.cinematic.replay.ReplayManager());
		ENGINE.addModule(new de.behindtheblocks.cinematic.studio.StudioManager());
		ENGINE.addModule(new DirectorManager());
		ENGINE.initialize();
		registerClientCueEvent();
		// Third-party integrations: "entrypoints": {"btb_cinematic": ["..."]}
		BtbCinematicApi api = new BtbCinematicApi(ENGINE);
		for (var container : FabricLoader.getInstance().getEntrypointContainers(MOD_ID, BtbCinematicPlugin.class)) {
			try {
				container.getEntrypoint().register(api);
				LOG.info("Loaded cinematic plugin from {}", container.getProvider().getMetadata().getId());
			} catch (RuntimeException e) {
				LOG.error("Cinematic plugin from {} failed", container.getProvider().getMetadata().getId(), e);
			}
		}
	}
}
