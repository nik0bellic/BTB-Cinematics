package de.behindtheblocks.cinematic.client.camera;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.camera.CameraPath;
import de.behindtheblocks.cinematic.camera.CameraPose;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.camera.OrbitShot;
import de.behindtheblocks.cinematic.client.ClientSceneState;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;
import de.behindtheblocks.cinematic.json.JsonParams;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the cinematic camera on the client.
 *
 * <p>{@code CameraMixin} asks {@link #pose(float, double, double, double, float, float)} every
 * frame after vanilla positioned the camera; when a shot is active the returned pose replaces the
 * player's view entirely, otherwise only shake offsets are added. While a shot plays the vanilla
 * HUD is hidden (cinematic overlays stay visible) and the perspective is switched to third person
 * so the hand isn't drawn; both are restored afterwards.
 */
public final class CinematicCameraController {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/client-camera");

	private record Shot(String sceneId, ClientTimeline.Stamp start, CameraPath path, OrbitShot orbit, int orbitEntity,
			int lookAtEntity, Vec3 lookAtPos, int duration, boolean hideHud, Follow follow) {
	}

	/**
	 * Follow camera: {@code side} (2.5D side view along a course), {@code behind} (chase cam),
	 * {@code fixed} (fixed position that pans with the target) or {@code top}.
	 */
	private record Follow(int entity, String mode, double distance, double height, Vec3 side, Vec3 fixedPos, double smoothing) {
	}

	private static Vec3 followPos;

	private record Shake(ClientTimeline.Stamp start, float intensity, int ticks, float frequency, float seed) {
	}

	private record Zoom(ClientTimeline.Stamp start, float from, float to, int ticks, Easing easing) {
	}

	private static Shot shot;
	private static final List<Shake> SHAKES = new ArrayList<>();
	private static Zoom zoom;
	/** Current zoom target FOV once a zoom finished (0 = none). */
	private static float heldZoom;
	private static CameraType savedCameraType;
	private static boolean savedHudHidden;
	private static CameraPose lastPose;

	private CinematicCameraController() {
	}

	public static void init() {
		ClientCueDispatcher.register("camera_play", CinematicCameraController::onPlay);
		ClientCueDispatcher.register("camera_stop", (scene, tick, data) -> stop());
		ClientCueDispatcher.register("camera_look_at", (scene, tick, data) -> {
			if (shot != null && data.has("entity")) {
				shot = new Shot(shot.sceneId, shot.start, shot.path, shot.orbit, shot.orbitEntity,
						data.get("entity").getAsInt(), null, shot.duration, shot.hideHud, shot.follow);
			}
		});
		ClientCueDispatcher.register("camera_shake", (scene, tick, data) -> {
			JsonParams p = new JsonParams(data);
			SHAKES.add(new Shake(ClientTimeline.Stamp.now(scene), p.getFloat("intensity", 0.5f), Math.max(1, p.getInt("ticks", 20)),
					p.getFloat("frequency", 1f), (float) (Math.random() * 1000)));
		});
		ClientCueDispatcher.register("camera_handheld", (scene, tick, data) -> {
			JsonParams p = new JsonParams(data);
			handheldTarget = Math.max(0f, Math.min(2f, p.getFloat("intensity", 0f)));
		});
		ClientCueDispatcher.register("camera_zoom", (scene, tick, data) -> {
			JsonParams p = new JsonParams(data);
			float current = currentZoomFov(0f);
			zoom = new Zoom(ClientTimeline.Stamp.now(scene), current, p.getFloat("fov", 0f), Math.max(1, p.getInt("ticks", 20)),
					Easing.byId(p.getString("easing", "ease_in_out")));
		});
		ClientCueDispatcher.register("camera_perspective", (scene, tick, data) -> {
			String mode = data.has("mode") ? data.get("mode").getAsString() : "first";
			Minecraft.getInstance().options.setCameraType(switch (mode) {
				case "third" -> CameraType.THIRD_PERSON_BACK;
				case "front" -> CameraType.THIRD_PERSON_FRONT;
				default -> CameraType.FIRST_PERSON;
			});
		});
		ClientSceneState.onSceneEnded((sceneId, reset) -> {
			if (reset) {
				if (shot != null && shot.sceneId.equals(sceneId)) {
					stop();
				}
				SHAKES.removeIf(s -> s.start.sceneId().equals(sceneId));
				if (zoom != null && zoom.start.sceneId().equals(sceneId)) {
					zoom = null;
					heldZoom = 0;
				}
			}
		});
	}

	private static void onPlay(String sceneId, int tick, JsonObject data) {
		JsonParams p = new JsonParams(data);
		String kind = p.requireString("kind");
		CameraPath path = null;
		OrbitShot orbit = null;
		Follow follow = null;
		int duration;
		if (kind.equals("follow")) {
			JsonParams f = new JsonParams(data.getAsJsonObject("follow"));
			double[] side = f.has("side") ? f.requireDoubleArray("side", 3) : new double[] {0, 0, 1};
			double[] fixed = f.has("pos") ? f.requireDoubleArray("pos", 3) : null;
			follow = new Follow(f.getInt("entity", -1), f.getString("mode", "side"), f.getDouble("distance", 8), f.getDouble("height", 2),
					new Vec3(side[0], side[1], side[2]).normalize(), fixed == null ? null : new Vec3(fixed[0], fixed[1], fixed[2]),
					Math.max(0.02, Math.min(1, f.getDouble("smoothing", 0.15))));
			int t = f.getInt("ticks", -1);
			duration = t < 0 ? Integer.MAX_VALUE : t;
			followPos = null;
			lastAutoShot = null;
			followStartMs = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
		} else if (kind.equals("path")) {
			path = CameraPath.fromJson("shot", data.getAsJsonObject("path"));
			duration = path.durationTicks();
		} else {
			orbit = OrbitShot.fromJson(new JsonParams(data.getAsJsonObject("orbit")));
			duration = orbit.ticks();
		}
		Vec3 lookPos = null;
		if (p.has("look_at_pos")) {
			double[] a = p.requireDoubleArray("look_at_pos", 3);
			lookPos = new Vec3(a[0], a[1], a[2]);
		}
		boolean hideHud = p.getBoolean("hide_hud", true);
		if (shot == null) {
			Minecraft mc = Minecraft.getInstance();
			savedCameraType = mc.options.getCameraType();
			savedHudHidden = mc.gui.hud.isHidden();
		}
		shot = new Shot(sceneId, ClientTimeline.Stamp.now(sceneId), path, orbit, p.getInt("orbit_entity", -1),
				p.getInt("look_at_entity", -1), lookPos, duration, hideHud, follow);
		applyViewSettings();
		LOG.debug("Camera shot started: {} ticks", duration);
	}

	private static void applyViewSettings() {
		Minecraft mc = Minecraft.getInstance();
		mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		if (shot.hideHud && !mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
		}
	}

	public static void stop() {
		if (shot == null) {
			return;
		}
		shot = null;
		lastPose = null;
		Minecraft mc = Minecraft.getInstance();
		if (savedCameraType != null) {
			mc.options.setCameraType(savedCameraType);
		}
		if (mc.gui.hud.isHidden() != savedHudHidden) {
			mc.gui.hud.toggle();
		}
	}

	public static boolean isActive() {
		return shot != null;
	}

	/** Client tick: end finished shots/shakes. */
	public static void tick() {
		if (shot != null && shot.start.elapsed(0f) > shot.duration) {
			stop();
		}
		SHAKES.removeIf(s -> s.start.elapsed(0f) > s.ticks);
		if (zoom != null && zoom.start.elapsed(0f) >= zoom.ticks) {
			heldZoom = zoom.to;
			zoom = null;
		}
	}

	/**
	 * Computes the final camera pose for this frame.
	 *
	 * @return the pose to use (vanilla pose plus shake if no shot is active)
	 */
	public static CameraPose pose(float partial, double vx, double vy, double vz, float vYaw, float vPitch) {
		Minecraft mc = Minecraft.getInstance();
		float defaultFov = mc.options.fov().get().floatValue();
		CameraPose base;
		if (shot != null) {
			double t = Math.min(shot.start.elapsed(partial), shot.duration);
			if (shot.follow != null) {
				CameraPose fp = followPose(mc, shot.follow, partial, defaultFov);
				base = fp != null ? fp : new CameraPose(vx, vy, vz, vYaw, vPitch, 0, 0);
			} else if (shot.path != null) {
				base = shot.path.evaluate(t, defaultFov);
			} else {
				Entity center = shot.orbitEntity >= 0 && mc.level != null ? mc.level.getEntity(shot.orbitEntity) : null;
				Vec3 c;
				if (center != null) {
					c = center.getPosition(partial);
					base = shot.orbit.evaluate(t, defaultFov, c.x, c.y, c.z);
					c = c.add(0, center.getBbHeight() * 0.6, 0);
				} else {
					base = shot.orbit.evaluate(t, defaultFov);
					c = new Vec3(shot.orbit.cx(), shot.orbit.cy() + 1, shot.orbit.cz());
				}
				// Orbits sweep around the subject: never let the lens pass through walls on the way.
				Vec3 safe = unblock(mc, c, new Vec3(base.x(), base.y(), base.z()));
				base = new CameraPose(safe.x, safe.y, safe.z, base.yaw(), base.pitch(), base.roll(), base.fov());
			}
			if (shot.lookAtEntity >= 0 && mc.level != null) {
				Entity target = mc.level.getEntity(shot.lookAtEntity);
				if (target != null) {
					Vec3 tp = target.getPosition(partial);
					base = base.lookingAt(tp.x, tp.y + target.getBbHeight() * 0.85, tp.z);
				}
			} else if (shot.lookAtPos != null) {
				base = base.lookingAt(shot.lookAtPos.x, shot.lookAtPos.y, shot.lookAtPos.z);
			}
		} else {
			CameraPose edit = de.behindtheblocks.cinematic.client.replay.ReplayEditor.pose(partial);
			base = edit != null ? edit : new CameraPose(vx, vy, vz, vYaw, vPitch, 0, 0);
		}
		lastPose = base;
		return applyShake(base, partial);
	}

	/** Focus distance set on the camera move at this moment (0 = none). */
	public static float pathFocus() {
		if (shot == null || shot.path == null) {
			return 0;
		}
		return shot.path.focusAt(Math.min(shot.start.elapsed(0f), shot.duration));
	}

	/** The actor the camera follows right now (for autofocus), or null. */
	public static Entity focusEntity() {
		Minecraft mc = Minecraft.getInstance();
		if (shot != null && shot.follow != null && mc.level != null) {
			return mc.level.getEntity(shot.follow.entity());
		}
		if (shot != null && shot.lookAtEntity >= 0 && mc.level != null) {
			return mc.level.getEntity(shot.lookAtEntity);
		}
		return null;
	}

	/** Auto director: shot list it cuts through (like a camera team switching angles). */
	private static final String[] AUTO_SHOTS = {"shoulder", "side", "front", "crane", "low", "behind", "orbit"};
	private static String lastAutoShot;
	private static long followStartMs;

	private static float egoYaw = Float.NaN, egoPitch;
	private static long egoLastMs;

	private static CameraPose followPose(Minecraft mc, Follow f, float partial, float defaultFov) {
		Entity target = mc.level == null ? null : mc.level.getEntity(f.entity());
		if (target == null) {
			return null;
		}
		if (followPos == null && lastAutoShot == null) {
			followStartMs = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
		}
		if (f.mode().equals("pov") || f.mode().equals("ego")) {
			// through the actor's eyes, a hair in front of the face
			Vec3 eye = target.getEyePosition(partial);
			float vy = target.getViewYRot(partial), vp = target.getViewXRot(partial);
			lastAutoShot = null;
			followPos = null;
			if (f.mode().equals("pov")) {
				Vec3 at = eye.add(Vec3.directionFromRotation(vp, vy).scale(0.25));
				return new CameraPose(at.x, at.y, at.z, vy, vp, 0, defaultFov);
			}
			// ego: a body cam – the head lags a little behind fast turns, steps bob, breathing sways
			long now = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
			float dt = egoLastMs == 0 ? 0.05f : Math.min(0.1f, (now - egoLastMs) / 1000f);
			egoLastMs = now;
			if (Float.isNaN(egoYaw)) {
				egoYaw = vy;
				egoPitch = vp;
			}
			float k = 1 - (float) Math.exp(-dt * 14);
			egoYaw += net.minecraft.util.Mth.wrapDegrees(vy - egoYaw) * k;
			egoPitch += (vp - egoPitch) * k;
			double walk = 0, speed = 0;
			if (target instanceof net.minecraft.world.entity.LivingEntity le) {
				walk = le.walkAnimation.position(partial);
				speed = Math.min(1, le.walkAnimation.speed(partial));
			}
			double t = now / 1000.0;
			double bobY = Math.abs(Math.sin(walk * 0.6662)) * 0.07 * speed - 0.035 * speed;
			double bobX = Math.sin(walk * 0.6662) * 0.035 * speed;
			float roll = (float) (Math.sin(walk * 0.6662) * 1.2 * speed + Math.sin(t * 0.9) * 0.25);
			float breathe = (float) (Math.sin(t * 1.6) * 0.35);
			Vec3 fw = Vec3.directionFromRotation(0, egoYaw);
			Vec3 right = new Vec3(-fw.z, 0, fw.x);
			Vec3 at = eye.add(Vec3.directionFromRotation(egoPitch, egoYaw).scale(0.2)).add(right.scale(bobX)).add(0, bobY, 0);
			return new CameraPose(at.x, at.y, at.z, egoYaw, egoPitch + breathe + (float) (bobY * 8), roll, defaultFov + 4 * (float) speed);
		}
		egoYaw = Float.NaN;
		Vec3 tp = target.getPosition(partial).add(0, target.getBbHeight() * 0.6, 0);
		float yaw = target.getViewYRot(partial);
		Vec3 fwd = Vec3.directionFromRotation(0, yaw);
		Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
		double secs = (de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() - followStartMs) / 1000.0;
		String mode = f.mode();
		if (mode.equals("auto")) {
			// height carries the shot length in seconds for "auto"
			double len = Math.max(0.5, f.height());
			mode = AUTO_SHOTS[(int) (secs / len) % AUTO_SHOTS.length];
			if (!mode.equals(lastAutoShot)) {
				followPos = null; // hard cut to the next angle
				lastAutoShot = mode;
			}
		} else {
			lastAutoShot = null;
		}
		double dist = mode.equals(f.mode()) ? f.distance() : Math.max(4, f.distance());
		double h = mode.equals(f.mode()) ? f.height() : 1.5;
		Vec3 look = tp;
		float roll = 0;
		Vec3 want = switch (mode) {
			case "behind" -> tp.add(fwd.scale(-dist)).add(0, h, 0);
			case "fixed" -> f.fixedPos() != null ? f.fixedPos() : tp.add(f.side().scale(dist));
			case "top" -> tp.add(0, dist, 0.01);
			case "front" -> {
				look = tp.add(0, 0.2, 0);
				yield tp.add(fwd.scale(dist * 0.8)).add(0, 0.3, 0);
			}
			case "shoulder" -> {
				look = tp.add(fwd.scale(8)).add(0, 0.4, 0);
				yield tp.add(fwd.scale(-2.6)).add(right.scale(0.9)).add(0, 0.55, 0);
			}
			case "low" -> {
				roll = 4;
				look = tp.add(0, 0.6, 0);
				yield tp.add(fwd.scale(-dist * 0.6)).add(right.scale(dist * 0.35)).add(0, -1.3, 0);
			}
			case "crane" -> {
				double a = Math.toRadians(yaw + 140 + secs * 9);
				yield tp.add(Math.cos(a) * dist * 1.3, dist * 0.9, Math.sin(a) * dist * 1.3);
			}
			case "orbit" -> {
				double a = Math.toRadians(secs * 22);
				yield tp.add(Math.cos(a) * dist, 0.8, Math.sin(a) * dist);
			}
			default -> f.mode().equals("auto") ? tp.add(right.scale(dist)).add(0, 1, 0) // side, relative to the actor
					: tp.add(f.side().scale(dist)).add(0, h, 0);
		};
		// Never inside walls or under lava: pull in towards the actor when something is in the way.
		boolean guard = !mode.equals("fixed");
		if (guard) {
			want = unblock(mc, tp, want);
		}
		// Frame-rate independent exponential smoothing → no jitter, gentle lag like a real operator.
		if (followPos == null) {
			followPos = want;
		} else {
			double k = 1 - Math.pow(1 - f.smoothing(), Math.max(0.2, mc.getDeltaTracker().getRealtimeDeltaTicks()));
			followPos = followPos.add(want.subtract(followPos).scale(k));
		}
		// The smoothed camera swings on a curve when the actor turns: that curve can cut through a wall
		// even when start and end are free. Check the actual lens position every frame as well.
		if (guard) {
			followPos = unblock(mc, tp, followPos);
		}
		CameraPose p = new CameraPose(followPos.x, followPos.y, followPos.z, 0, 0, 0, defaultFov).lookingAt(look.x, look.y, look.z);
		return roll == 0 ? p : new CameraPose(p.x(), p.y(), p.z(), p.yaw(), p.pitch(), roll, p.fov());
	}

	/** Lens half-size: rays are cast from the corners of this box, so the near plane never touches a wall. */
	private static final double LENS = 0.22;

	/**
	 * Returns {@code to}, or the furthest point from {@code from} towards it that keeps a small box around the
	 * lens free of blocks (like the vanilla third-person camera, but stricter, so nothing can be seen through walls).
	 */
	private static Vec3 unblock(Minecraft mc, Vec3 from, Vec3 to) {
		if (mc.level == null) {
			return to;
		}
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 1e-3) {
			return to;
		}
		double best = len;
		for (int i = 0; i < 9; i++) {
			double ox = i == 8 ? 0 : ((i & 1) * 2 - 1) * LENS;
			double oy = i == 8 ? 0 : ((i >> 1 & 1) * 2 - 1) * LENS;
			double oz = i == 8 ? 0 : ((i >> 2 & 1) * 2 - 1) * LENS;
			Vec3 a = from.add(ox, oy, oz);
			Vec3 b = to.add(ox, oy, oz);
			var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(a, b, net.minecraft.world.level.ClipContext.Block.VISUAL,
					net.minecraft.world.level.ClipContext.Fluid.ANY, net.minecraft.world.phys.shapes.CollisionContext.empty()));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
				best = Math.min(best, hit.getLocation().distanceTo(a));
			}
		}
		if (best >= len) {
			return to;
		}
		double keep = Math.max(0, best - LENS);
		return from.add(d.scale(keep / len));
	}

	/** Handheld camera: a slow, breathing drift (operator holding the camera), eased in and out. */
	private static volatile float handheldTarget;
	private static float handheld;
	private static long handheldLastMs;

	private static CameraPose applyShake(CameraPose p, float partial) {
		long now = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
		float dt = handheldLastMs == 0 ? 0 : Math.min(0.2f, (now - handheldLastMs) / 1000f);
		handheldLastMs = now;
		handheld += (handheldTarget - handheld) * Math.min(1f, dt * 1.5f);
		if (SHAKES.isEmpty() && handheld < 0.002f) {
			return p;
		}
		double yaw = 0, pitch = 0, roll = 0, ox = 0, oy = 0, oz = 0;
		if (handheld >= 0.002f) {
			double k = now / 1000.0;
			double a = handheld;
			// slow sway (breathing) + small faster corrections, never periodic
			yaw += a * (0.9 * Math.sin(k * 0.71) + 0.45 * Math.sin(k * 1.93 + 1.3) + 0.15 * Math.sin(k * 4.7 + 0.4));
			pitch += a * (0.7 * Math.sin(k * 0.53 + 2.1) + 0.35 * Math.sin(k * 2.31 + 0.7) + 0.12 * Math.sin(k * 5.3 + 2.2));
			roll += a * (0.5 * Math.sin(k * 0.37 + 4.0) + 0.2 * Math.sin(k * 1.61 + 0.2));
			oy += a * 0.025 * Math.sin(k * 1.1 + 0.9);
			ox += a * 0.02 * Math.sin(k * 0.83 + 3.3);
		}
		for (Shake s : SHAKES) {
			double t = s.start.elapsed(partial);
			double life = 1.0 - Math.min(1.0, t / s.ticks);
			double amp = s.intensity * life * life; // quadratic falloff feels natural
			double f = s.frequency * 0.9;
			double k = t * f + s.seed;
			// Sum of incommensurate sines = smooth, non-repeating noise.
			yaw += amp * 2.2 * (Math.sin(k * 1.31) + 0.5 * Math.sin(k * 3.17));
			pitch += amp * 1.6 * (Math.sin(k * 1.73 + 2) + 0.5 * Math.sin(k * 2.93 + 1));
			roll += amp * 1.2 * Math.sin(k * 1.07 + 4);
			ox += amp * 0.04 * Math.sin(k * 2.41 + 3);
			oy += amp * 0.04 * Math.sin(k * 2.83 + 5);
			oz += amp * 0.04 * Math.sin(k * 2.17 + 7);
		}
		return new CameraPose(p.x() + ox, p.y() + oy, p.z() + oz, (float) (p.yaw() + yaw),
				(float) Math.max(-90, Math.min(90, p.pitch() + pitch)), (float) (p.roll() + roll), p.fov());
	}

	/** FOV for this frame given vanilla's computed value. */
	public static float fov(float vanillaFov, float partial) {
		float result = vanillaFov;
		if ((shot != null || de.behindtheblocks.cinematic.client.replay.ReplayEditor.pose(partial) != null) && lastPose != null && lastPose.fov() > 0) {
			// The path stores absolute FOVs; keep vanilla's dynamic modifiers (sprint etc.) out of shots.
			result = lastPose.fov();
		}
		float zoomFov = currentZoomFov(partial);
		if (zoomFov > 0) {
			result = zoomFov;
		}
		return result;
	}

	private static float currentZoomFov(float partial) {
		if (zoom == null) {
			return heldZoom;
		}
		Minecraft mc = Minecraft.getInstance();
		float normal = mc.options.fov().get().floatValue();
		float from = zoom.from > 0 ? zoom.from : normal;
		float to = zoom.to > 0 ? zoom.to : normal;
		double t = zoom.easing.apply(zoom.start.elapsed(partial) / zoom.ticks);
		float v = (float) (from + (to - from) * t);
		if (zoom.to == 0 && t >= 1) {
			return 0;
		}
		return v;
	}

	/** Called on disconnect. */
	public static void reset() {
		stop();
		SHAKES.clear();
		zoom = null;
		heldZoom = 0;
	}
}
