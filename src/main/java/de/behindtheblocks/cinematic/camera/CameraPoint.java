package de.behindtheblocks.cinematic.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;

/**
 * A keyframe on a camera path.
 *
 * @param ticks   travel time from this point to the next one (ignored on the last point)
 * @param easing  easing applied to the segment that starts at this point
 * @param fov     field of view at this point; 0 = player's setting
 * @param handleOut optional Bézier handle (offset from the point) leaving this point, or null
 * @param handleIn  optional Bézier handle (offset from the point) arriving at this point, or null
 */
public record CameraPoint(double x, double y, double z, float yaw, float pitch, float roll, float fov,
		int ticks, Easing easing, double[] handleIn, double[] handleOut, float focus) {

	/** Without focus distance (0 = depth of field focuses automatically). */
	public CameraPoint(double x, double y, double z, float yaw, float pitch, float roll, float fov,
			int ticks, Easing easing, double[] handleIn, double[] handleOut) {
		this(x, y, z, yaw, pitch, roll, fov, ticks, easing, handleIn, handleOut, 0f);
	}

	/** Sharp distance (blocks) the depth of field pulls to at this point; 0 = automatic. */
	public CameraPoint withFocus(float newFocus) {
		return new CameraPoint(x, y, z, yaw, pitch, roll, fov, ticks, easing, handleIn, handleOut, newFocus);
	}

	public static final int DEFAULT_TICKS = 60;

	public CameraPoint withTicks(int newTicks) {
		return new CameraPoint(x, y, z, yaw, pitch, roll, fov, newTicks, easing, handleIn, handleOut, focus);
	}

	public CameraPoint withEasing(Easing newEasing) {
		return new CameraPoint(x, y, z, yaw, pitch, roll, fov, ticks, newEasing, handleIn, handleOut, focus);
	}

	public CameraPoint withFov(float newFov) {
		return new CameraPoint(x, y, z, yaw, pitch, roll, newFov, ticks, easing, handleIn, handleOut, focus);
	}

	public CameraPoint withRoll(float newRoll) {
		return new CameraPoint(x, y, z, yaw, pitch, newRoll, fov, ticks, easing, handleIn, handleOut, focus);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonArray pos = new JsonArray();
		pos.add(x);
		pos.add(y);
		pos.add(z);
		o.add("pos", pos);
		o.addProperty("yaw", yaw);
		o.addProperty("pitch", pitch);
		if (roll != 0) {
			o.addProperty("roll", roll);
		}
		if (fov != 0) {
			o.addProperty("fov", fov);
		}
		if (focus != 0) {
			o.addProperty("focus", focus);
		}
		o.addProperty("ticks", ticks);
		if (easing != Easing.LINEAR) {
			o.addProperty("easing", easing.id());
		}
		if (handleIn != null) {
			o.add("handle_in", vec(handleIn));
		}
		if (handleOut != null) {
			o.add("handle_out", vec(handleOut));
		}
		return o;
	}

	private static JsonArray vec(double[] v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}

	public static CameraPoint fromJson(JsonParams p) {
		double[] pos = p.requireDoubleArray("pos", 3);
		int ticks = p.getInt("ticks", DEFAULT_TICKS);
		if (ticks < 1) {
			throw new JsonParamException("'ticks' must be at least 1");
		}
		float fov = p.getFloat("fov", 0f);
		if (fov != 0 && (fov < 1 || fov > 170)) {
			throw new JsonParamException("'fov' must be between 1 and 170 (or 0 for default)");
		}
		Easing easing;
		try {
			easing = Easing.byId(p.getString("easing", "linear"));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException(e.getMessage());
		}
		return new CameraPoint(pos[0], pos[1], pos[2], p.getFloat("yaw", 0f), p.getFloat("pitch", 0f),
				p.getFloat("roll", 0f), fov, ticks, easing,
				p.has("handle_in") ? p.requireDoubleArray("handle_in", 3) : null,
				p.has("handle_out") ? p.requireDoubleArray("handle_out", 3) : null, p.getFloat("focus", 0f));
	}
}
