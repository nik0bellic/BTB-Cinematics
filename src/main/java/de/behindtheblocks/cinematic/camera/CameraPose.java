package de.behindtheblocks.cinematic.camera;

/**
 * A camera state at one instant. Angles in degrees, Minecraft convention (yaw 0 = south,
 * pitch +90 = straight down). {@code fov} of 0 means "use the player's FOV setting".
 */
public record CameraPose(double x, double y, double z, float yaw, float pitch, float roll, float fov) {
	/** Returns a copy whose yaw/pitch look from this position at the given point. */
	public CameraPose lookingAt(double tx, double ty, double tz) {
		double dx = tx - x, dy = ty - y, dz = tz - z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float newYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float newPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		return new CameraPose(x, y, z, newYaw, newPitch, roll, fov);
	}
}
