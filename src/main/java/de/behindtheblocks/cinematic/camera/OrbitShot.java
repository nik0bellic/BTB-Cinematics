package de.behindtheblocks.cinematic.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;

/**
 * Circular (or spiral) camera move around a center, always looking at the center.
 *
 * @param startAngle degrees; 0 = camera south of the center (+Z), increasing counter-clockwise seen from above
 * @param degrees    total sweep, may be negative (clockwise) or > 360
 * @param radiusEnd  radius at the end (equal to {@code radius} for a plain circle)
 * @param heightEnd  camera height above center at the end
 * @param lookOffset vertical offset of the look-at point above the center
 */
public record OrbitShot(double cx, double cy, double cz, double radius, double radiusEnd, double height,
		double heightEnd, double startAngle, double degrees, int ticks, Easing easing, float fov, double lookOffset) {

	public CameraPose evaluate(double time, float defaultFov, double centerX, double centerY, double centerZ) {
		double t = easing.apply(Math.max(0, Math.min(1, time / ticks)));
		double angle = Math.toRadians(startAngle + degrees * t);
		double r = radius + (radiusEnd - radius) * t;
		double h = height + (heightEnd - height) * t;
		double x = centerX + Math.sin(angle) * r;
		double z = centerZ + Math.cos(angle) * r;
		double y = centerY + h;
		return new CameraPose(x, y, z, 0, 0, 0, fov == 0 ? defaultFov : fov)
				.lookingAt(centerX, centerY + lookOffset, centerZ);
	}

	public CameraPose evaluate(double time, float defaultFov) {
		return evaluate(time, defaultFov, cx, cy, cz);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonArray c = new JsonArray();
		c.add(cx);
		c.add(cy);
		c.add(cz);
		o.add("center", c);
		o.addProperty("radius", radius);
		o.addProperty("radius_end", radiusEnd);
		o.addProperty("height", height);
		o.addProperty("height_end", heightEnd);
		o.addProperty("start_angle", startAngle);
		o.addProperty("degrees", degrees);
		o.addProperty("ticks", ticks);
		o.addProperty("easing", easing.id());
		o.addProperty("fov", fov);
		o.addProperty("look_offset", lookOffset);
		return o;
	}

	public static OrbitShot fromJson(JsonParams p) {
		double[] c = p.requireDoubleArray("center", 3);
		double radius = p.getDouble("radius", 6);
		if (radius <= 0 || radius > 512) {
			throw new JsonParamException("'radius' must be between 0 and 512");
		}
		int ticks = p.has("ticks") ? p.requireInt("ticks") : (int) Math.round(p.getDouble("seconds", 10) * 20);
		if (ticks < 1) {
			throw new JsonParamException("orbit duration must be at least 1 tick");
		}
		Easing easing;
		try {
			easing = Easing.byId(p.getString("easing", "ease_in_out"));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException(e.getMessage());
		}
		double height = p.getDouble("height", 2);
		return new OrbitShot(c[0], c[1], c[2], radius, p.getDouble("radius_end", radius), height,
				p.getDouble("height_end", height), p.getDouble("start_angle", 0), p.getDouble("degrees", 360),
				ticks, easing, p.getFloat("fov", 0f), p.getDouble("look_offset", 0.5));
	}
}
