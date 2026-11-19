package de.behindtheblocks.cinematic.client.sound;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.client.ClientSceneState;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.sound.ReverbPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client side of cinematic sound: one-shot sounds (positional or not), echoes, background music
 * with cross-fades, a master gain and the reverb preset.
 */
public final class CinematicSoundPlayer {
	private record PendingEcho(String sceneId, double at, Identifier sound, float volume, float pitch, Vec3 pos) {
	}

	private static final List<CinematicSoundInstance> ACTIVE = new ArrayList<>();
	private static final List<PendingEcho> ECHOES = new ArrayList<>();
	private static CinematicSoundInstance music;

	private static float gainFrom = 1f, gainTo = 1f;
	private static ClientTimeline.Stamp gainStart;
	private static int gainTicks;

	private CinematicSoundPlayer() {
	}

	public static void init() {
		ClientCueDispatcher.register("sound_play", CinematicSoundPlayer::onPlay);
		ClientCueDispatcher.register("sound_stop", CinematicSoundPlayer::onStop);
		ClientCueDispatcher.register("music", CinematicSoundPlayer::onMusic);
		ClientCueDispatcher.register("reverb", (scene, tick, data) -> {
			JsonParams p = new JsonParams(data);
			CinematicReverb.set(ReverbPreset.byId(p.getString("preset", "off")), p.getFloat("mix", 0.6f));
		});
		ClientCueDispatcher.register("sound_volume", (scene, tick, data) -> {
			JsonParams p = new JsonParams(data);
			gainFrom = masterGain();
			gainTo = Math.max(0, Math.min(1, p.getFloat("volume", 1f)));
			gainTicks = Math.max(0, p.getInt("fade", 20));
			gainStart = ClientTimeline.Stamp.now("");
		});
		ClientSceneState.onSceneEnded((sceneId, reset) -> {
			if (reset) {
				ECHOES.removeIf(e -> e.sceneId.equals(sceneId));
			}
		});
	}

	public static float masterGain() {
		if (gainStart == null) {
			return gainTo;
		}
		double p = gainTicks <= 0 ? 1 : Math.min(1, gainStart.elapsed(0f) / gainTicks);
		return (float) (gainFrom + (gainTo - gainFrom) * Easing.EASE_IN_OUT.apply(p));
	}

	private static Vec3 pos(JsonParams p) {
		if (!p.has("pos")) {
			return null;
		}
		double[] a = p.requireDoubleArray("pos", 3);
		return new Vec3(a[0], a[1], a[2]);
	}

	private static void onPlay(String sceneId, int tick, JsonObject data) {
		JsonParams p = new JsonParams(data);
		Identifier id = Identifier.tryParse(p.requireString("sound"));
		if (id == null) {
			return;
		}
		float volume = p.getFloat("volume", 1f), pitch = p.getFloat("pitch", 1f);
		Vec3 pos = pos(p);
		start(new CinematicSoundInstance(id, sceneId, SoundSource.MASTER, volume, pitch, pos, false, p.getInt("fade_in", 0)));
		p.getObject("echo").ifPresent(echo -> {
			JsonParams e = new JsonParams(echo);
			int count = e.getInt("count", 3), delay = e.getInt("delay", 8);
			float decay = e.getFloat("decay", 0.5f);
			double now = ClientTimeline.now(sceneId, 0f);
			float v = volume;
			for (int i = 1; i <= count; i++) {
				v *= decay;
				// Each repeat slightly darker/lower, like a real reflection.
				ECHOES.add(new PendingEcho(sceneId, now + (double) i * delay, id, v, pitch * (1 - 0.015f * i), pos));
			}
		});
	}

	private static void start(CinematicSoundInstance instance) {
		Minecraft.getInstance().getSoundManager().play(instance);
		ACTIVE.add(instance);
	}

	private static void onStop(String sceneId, int tick, JsonObject data) {
		JsonParams p = new JsonParams(data);
		String sound = p.has("sound") ? p.requireString("sound") : null;
		boolean sceneOnly = p.getBoolean("scene_only", false);
		int fade = p.getInt("fade", 10);
		for (CinematicSoundInstance s : ACTIVE) {
			if ((sound == null || s.soundId.toString().equals(sound)) && (!sceneOnly || s.sceneId.equals(sceneId))) {
				s.fadeOut(fade);
			}
		}
		if (sound == null) {
			ECHOES.removeIf(e -> !sceneOnly || e.sceneId.equals(sceneId));
		}
	}

	private static void onMusic(String sceneId, int tick, JsonObject data) {
		JsonParams p = new JsonParams(data);
		int fade = p.getInt("fade", 40);
		if (music != null) {
			music.fadeOut(fade); // cross-fade: old track fades while the new one rises
			music = null;
		}
		if (p.getBoolean("stop", false)) {
			return;
		}
		Identifier id = Identifier.tryParse(p.requireString("sound"));
		if (id == null) {
			return;
		}
		Minecraft.getInstance().getMusicManager().stopPlaying(); // keep vanilla music out of the shot
		music = new CinematicSoundInstance(id, sceneId, SoundSource.MUSIC, p.getFloat("volume", 1f), 1f, null,
				p.getBoolean("loop", true), fade);
		start(music);
	}

	/** True while cinematic music plays; vanilla music is suppressed meanwhile. */
	public static boolean musicActive() {
		return music != null;
	}

	public static void tick() {
		var sm = Minecraft.getInstance().getSoundManager();
		// Finished one-shots never call stop(); drop them once the engine no longer plays them.
		ACTIVE.removeIf(s -> s.isStopped() || (s.age() > 10 && !sm.isActive(s)));
		if (music != null && music.isStopped()) {
			music = null;
		}
		if (ECHOES.isEmpty()) {
			return;
		}
		Iterator<PendingEcho> it = ECHOES.iterator();
		List<PendingEcho> due = new ArrayList<>();
		while (it.hasNext()) {
			PendingEcho e = it.next();
			if (ClientTimeline.now(e.sceneId, 0f) >= e.at) {
				due.add(e);
				it.remove();
			}
		}
		for (PendingEcho e : due) {
			start(new CinematicSoundInstance(e.sound, e.sceneId, SoundSource.MASTER, e.volume, e.pitch, e.pos, false, 0));
		}
	}

	public static void reset() {
		ACTIVE.forEach(CinematicSoundInstance::stopNow);
		ACTIVE.clear();
		ECHOES.clear();
		music = null;
		gainStart = null;
		gainTo = 1f;
		CinematicReverb.set(ReverbPreset.OFF, 0.6f);
	}
}
