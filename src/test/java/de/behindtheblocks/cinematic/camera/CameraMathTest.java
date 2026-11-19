package de.behindtheblocks.cinematic.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Camera path and orbit math (shared by server and client, so it must be exact and deterministic). */
class CameraMathTest {
	private static CameraPoint p(double x, double y, double z, float yaw, int ticks) {
		return new CameraPoint(x, y, z, yaw, 0, 0, 0, ticks, Easing.LINEAR, null, null);
	}

	@Test
	void pathHitsKeyframesExactly() {
		for (CameraPath.Mode mode : CameraPath.Mode.values()) {
			CameraPath path = new CameraPath("t", List.of(p(0, 0, 0, 0, 20), p(10, 5, 0, 90, 20), p(10, 5, 10, 180, 1)), mode, Easing.LINEAR, 1);
			assertEquals(40, path.durationTicks());
			CameraPose start = path.evaluate(0, 70), mid = path.evaluate(20, 70), end = path.evaluate(40, 70);
			assertEquals(0, start.x(), 1e-9, mode.id());
			assertEquals(10, mid.x(), 1e-9, mode.id());
			assertEquals(5, mid.y(), 1e-9, mode.id());
			assertEquals(10, end.z(), 1e-9, mode.id());
			assertEquals(180, end.yaw(), 1e-4, mode.id());
		}
	}

	@Test
	void linearIsHalfwayAtHalfTime() {
		CameraPath path = new CameraPath("t", List.of(p(0, 0, 0, 0, 10), p(10, 0, 0, 0, 1)), CameraPath.Mode.LINEAR, Easing.LINEAR, 1);
		assertEquals(5, path.evaluate(5, 70).x(), 1e-9);
	}

	@Test
	void speedScalesDuration() {
		CameraPath path = new CameraPath("t", List.of(p(0, 0, 0, 0, 100), p(1, 0, 0, 0, 1)), CameraPath.Mode.LINEAR, Easing.LINEAR, 2);
		assertEquals(50, path.durationTicks());
		assertEquals(1, path.evaluate(50, 70).x(), 1e-9);
	}

	@Test
	void yawTakesShortestWayAcross180() {
		CameraPath path = new CameraPath("t", List.of(p(0, 0, 0, 170, 10), p(0, 0, 0, -170, 1)), CameraPath.Mode.LINEAR, Easing.LINEAR, 1);
		float mid = path.evaluate(5, 70).yaw();
		assertEquals(180, ((mid % 360) + 360) % 360, 1e-3, "must pass through 180, not 0");
	}

	@Test
	void defaultFovFillsZero() {
		CameraPath path = new CameraPath("t", List.of(p(0, 0, 0, 0, 10), p(1, 0, 0, 0, 1)), CameraPath.Mode.SMOOTH, Easing.LINEAR, 1);
		assertEquals(70, path.evaluate(3, 70).fov(), 1e-4);
	}

	@Test
	void easingEndpoints() {
		for (Easing e : Easing.values()) {
			assertEquals(0, e.apply(0), 1e-9, e.id());
			assertEquals(1, e.apply(1), 1e-9, e.id());
		}
	}

	@Test
	void jsonRoundTrip() {
		CameraPath path = new CameraPath("intro", List.of(p(1, 2, 3, 45, 30), p(4, 5, 6, -45, 1)), CameraPath.Mode.BEZIER, Easing.EASE_IN_OUT, 1.5);
		CameraPath back = CameraPath.fromJson("x", path.toJson());
		assertEquals("intro", back.id());
		assertEquals(CameraPath.Mode.BEZIER, back.mode());
		assertEquals(path.evaluate(13, 70), back.evaluate(13, 70));
	}

	@Test
	void orbitLooksAtCentreAndKeepsRadius() {
		OrbitShot o = new OrbitShot(0, 0, 0, 5, 5, 0, 0, 0, 360, 100, Easing.LINEAR, 0, 0);
		for (int t = 0; t <= 100; t += 25) {
			CameraPose pose = o.evaluate(t, 70);
			assertEquals(5, Math.hypot(pose.x(), pose.z()), 1e-9);
			CameraPose expected = new CameraPose(pose.x(), pose.y(), pose.z(), 0, 0, 0, 0).lookingAt(0, 0, 0);
			assertEquals(expected.yaw(), pose.yaw(), 1e-4);
		}
	}

	@Test
	void lookingAtUsesMinecraftConventions() {
		CameraPose south = new CameraPose(0, 0, 0, 0, 0, 0, 0).lookingAt(0, 0, 10);
		assertEquals(0, south.yaw(), 1e-4); // yaw 0 = +Z
		CameraPose down = new CameraPose(0, 10, 0, 0, 0, 0, 0).lookingAt(0, 0, 0.0001);
		assertTrue(down.pitch() > 89); // positive pitch = looking down
	}
}
