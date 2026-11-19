package de.behindtheblocks.cinematic.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An immutable camera path through keyframes. Evaluation is pure and deterministic, so the
 * server (chunk carrying, durations) and every client compute exactly the same pose.
 *
 * <h2>Interpolation</h2>
 * <ul>
 *   <li>{@code linear} — straight lines, constant speed per segment.</li>
 *   <li>{@code smooth} — Catmull-Rom spline through all points (C1 continuous, no corners).</li>
 *   <li>{@code bezier} — cubic Bézier per segment; per-point {@code handle_in}/{@code handle_out}
 *       override the automatic (Catmull-Rom equivalent) handles.</li>
 * </ul>
 * Timing: each point's {@code ticks} is the travel time to the next point; the segment easing
 * shapes motion inside a segment, the path {@code easing} shapes the whole move; {@code speed}
 * scales the total duration (2.0 = twice as fast).
 */
public final class CameraPath {
	public enum Mode {
		LINEAR, SMOOTH, BEZIER;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Mode byId(String id) {
			for (Mode m : values()) {
				if (m.id().equals(id.toLowerCase(Locale.ROOT))) {
					return m;
				}
			}
			throw new IllegalArgumentException("unknown path mode '" + id + "' (linear, smooth, bezier)");
		}
	}

	private final String id;
	private final List<CameraPoint> points;
	private final Mode mode;
	private final Easing easing;
	private final double speed;
	private final float[] unwrappedYaw;
	private final int rawTicks;

	public CameraPath(String id, List<CameraPoint> points, Mode mode, Easing easing, double speed) {
		if (points.isEmpty()) {
			throw new IllegalArgumentException("camera path needs at least one point");
		}
		if (!(speed > 0) || speed > 100) {
			throw new IllegalArgumentException("speed must be > 0 and <= 100");
		}
		this.id = id;
		this.points = List.copyOf(points);
		this.mode = mode;
		this.easing = easing;
		this.speed = speed;
		this.unwrappedYaw = new float[points.size()];
		unwrappedYaw[0] = points.get(0).yaw();
		for (int i = 1; i < points.size(); i++) {
			float delta = wrapDegrees(points.get(i).yaw() - unwrappedYaw[i - 1]);
			unwrappedYaw[i] = unwrappedYaw[i - 1] + delta;
		}
		int sum = 0;
		for (int i = 0; i < points.size() - 1; i++) {
			sum += points.get(i).ticks();
		}
		this.rawTicks = sum;
	}

	/** Total playback duration in ticks after applying {@link #speed()} (at least 1). */
	public int durationTicks() {
		return Math.max(1, (int) Math.ceil(rawTicks / speed));
	}

	/**
	 * Evaluates the pose at {@code time} ticks after the start (fractions allowed for smooth
	 * per-frame rendering). Times beyond the end clamp to the last point.
	 *
	 * @param defaultFov FOV to use where a point says 0 ("player setting")
	 */
	public CameraPose evaluate(double time, float defaultFov) {
		if (points.size() == 1 || rawTicks == 0) {
			CameraPoint p = points.get(points.size() - 1);
			return new CameraPose(p.x(), p.y(), p.z(), p.yaw(), p.pitch(), p.roll(), fovOf(p, defaultFov));
		}
		double duration = rawTicks / speed;
		double global = easing.apply(Math.max(0, Math.min(1, time / duration))) * rawTicks;

		int seg = 0;
		double start = 0;
		while (seg < points.size() - 2 && global >= start + points.get(seg).ticks()) {
			start += points.get(seg).ticks();
			seg++;
		}
		CameraPoint a = points.get(seg);
		double u = Math.max(0, Math.min(1, (global - start) / a.ticks()));
		u = a.easing().apply(u);
		return interpolate(seg, u, defaultFov);
	}

	/** Focus distance at {@code time} (interpolated between points that have one), 0 = automatic. */
	public float focusAt(double time) {
		if (points.size() == 1 || rawTicks == 0) {
			return points.get(0).focus();
		}
		double duration = rawTicks / speed;
		double global = easing.apply(Math.max(0, Math.min(1, time / duration))) * rawTicks;
		int seg = 0;
		double start = 0;
		while (seg < points.size() - 2 && global >= start + points.get(seg).ticks()) {
			start += points.get(seg).ticks();
			seg++;
		}
		float a = points.get(seg).focus(), b = points.get(seg + 1).focus();
		if (a == 0 && b == 0) {
			return 0;
		}
		if (a == 0) {
			a = b;
		}
		if (b == 0) {
			b = a;
		}
		double u = Math.max(0, Math.min(1, (global - start) / points.get(seg).ticks()));
		return (float) (a + (b - a) * u);
	}

	private CameraPose interpolate(int i, double u, float defaultFov) {
		CameraPoint p1 = points.get(i);
		CameraPoint p2 = points.get(i + 1);
		CameraPoint p0 = points.get(Math.max(0, i - 1));
		CameraPoint p3 = points.get(Math.min(points.size() - 1, i + 2));
		double y0 = unwrappedYaw[Math.max(0, i - 1)], y1 = unwrappedYaw[i], y2 = unwrappedYaw[i + 1],
				y3 = unwrappedYaw[Math.min(points.size() - 1, i + 2)];
		double f0 = fovOf(p0, defaultFov), f1 = fovOf(p1, defaultFov), f2 = fovOf(p2, defaultFov), f3 = fovOf(p3, defaultFov);

		if (mode == Mode.LINEAR) {
			return new CameraPose(lerp(p1.x(), p2.x(), u), lerp(p1.y(), p2.y(), u), lerp(p1.z(), p2.z(), u),
					(float) lerp(y1, y2, u), (float) lerp(p1.pitch(), p2.pitch(), u),
					(float) lerp(p1.roll(), p2.roll(), u), (float) lerp(f1, f2, u));
		}

		double x, y, z;
		if (mode == Mode.BEZIER) {
			double[] c1 = p1.handleOut() != null
					? new double[] {p1.x() + p1.handleOut()[0], p1.y() + p1.handleOut()[1], p1.z() + p1.handleOut()[2]}
					: new double[] {p1.x() + (p2.x() - p0.x()) / 6, p1.y() + (p2.y() - p0.y()) / 6, p1.z() + (p2.z() - p0.z()) / 6};
			double[] c2 = p2.handleIn() != null
					? new double[] {p2.x() + p2.handleIn()[0], p2.y() + p2.handleIn()[1], p2.z() + p2.handleIn()[2]}
					: new double[] {p2.x() - (p3.x() - p1.x()) / 6, p2.y() - (p3.y() - p1.y()) / 6, p2.z() - (p3.z() - p1.z()) / 6};
			x = bezier(p1.x(), c1[0], c2[0], p2.x(), u);
			y = bezier(p1.y(), c1[1], c2[1], p2.y(), u);
			z = bezier(p1.z(), c1[2], c2[2], p2.z(), u);
		} else {
			x = catmullRom(p0.x(), p1.x(), p2.x(), p3.x(), u);
			y = catmullRom(p0.y(), p1.y(), p2.y(), p3.y(), u);
			z = catmullRom(p0.z(), p1.z(), p2.z(), p3.z(), u);
		}
		float pitch = (float) Math.max(-90, Math.min(90, catmullRom(p0.pitch(), p1.pitch(), p2.pitch(), p3.pitch(), u)));
		return new CameraPose(x, y, z,
				(float) catmullRom(y0, y1, y2, y3, u), pitch,
				(float) catmullRom(p0.roll(), p1.roll(), p2.roll(), p3.roll(), u),
				(float) Math.max(1, catmullRom(f0, f1, f2, f3, u)));
	}

	private static float fovOf(CameraPoint p, float defaultFov) {
		return p.fov() == 0 ? defaultFov : p.fov();
	}

	static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	static double catmullRom(double p0, double p1, double p2, double p3, double t) {
		double t2 = t * t, t3 = t2 * t;
		return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
	}

	static double bezier(double p0, double c0, double c1, double p1, double t) {
		double mt = 1 - t;
		return mt * mt * mt * p0 + 3 * mt * mt * t * c0 + 3 * mt * t * t * c1 + t * t * t * p1;
	}

	static float wrapDegrees(float deg) {
		float d = deg % 360f;
		if (d >= 180f) {
			d -= 360f;
		}
		if (d < -180f) {
			d += 360f;
		}
		return d;
	}

	// ------------------------------------------------------------------ accessors & copies

	public String id() {
		return id;
	}

	public List<CameraPoint> points() {
		return points;
	}

	public Mode mode() {
		return mode;
	}

	public Easing easing() {
		return easing;
	}

	public double speed() {
		return speed;
	}

	public CameraPath withPoints(List<CameraPoint> newPoints) {
		return new CameraPath(id, newPoints, mode, easing, speed);
	}

	public CameraPath withMode(Mode newMode) {
		return new CameraPath(id, points, newMode, easing, speed);
	}

	public CameraPath withEasing(Easing newEasing) {
		return new CameraPath(id, points, mode, newEasing, speed);
	}

	public CameraPath withSpeed(double newSpeed) {
		return new CameraPath(id, points, mode, easing, newSpeed);
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("mode", mode.id());
		o.addProperty("easing", easing.id());
		o.addProperty("speed", speed);
		JsonArray arr = new JsonArray();
		points.forEach(p -> arr.add(p.toJson()));
		o.add("points", arr);
		return o;
	}

	public static CameraPath fromJson(String fallbackId, JsonObject json) {
		JsonParams p = new JsonParams(json);
		String id = p.getString("id", fallbackId);
		Mode mode;
		Easing easing;
		try {
			mode = Mode.byId(p.getString("mode", "smooth"));
			easing = Easing.byId(p.getString("easing", "linear"));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException(e.getMessage());
		}
		double speed = p.getDouble("speed", 1.0);
		if (!json.has("points") || !json.get("points").isJsonArray()) {
			throw new JsonParamException("'points' must be an array");
		}
		List<CameraPoint> pts = new ArrayList<>();
		int i = 0;
		for (JsonElement e : json.getAsJsonArray("points")) {
			if (!e.isJsonObject()) {
				throw new JsonParamException("point #" + (i + 1) + " must be an object");
			}
			try {
				pts.add(CameraPoint.fromJson(new JsonParams(e.getAsJsonObject())));
			} catch (JsonParamException ex) {
				throw new JsonParamException("point #" + (i + 1) + ": " + ex.getMessage());
			}
			i++;
		}
		if (pts.isEmpty()) {
			throw new JsonParamException("path has no points");
		}
		try {
			return new CameraPath(id, pts, mode, easing, speed);
		} catch (IllegalArgumentException ex) {
			throw new JsonParamException(ex.getMessage());
		}
	}
}
