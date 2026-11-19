package de.behindtheblocks.cinematic.client.director;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Floor marks for the actors (drawn with client-side particles, so only this client sees them) and
 * the global wind that drifts every particle. The server never sends marks to a camera player; the
 * clean feed (F8) hides them as well.
 */
public final class CrewMarkers {
	private record Mark(String name, double x, double y, double z, int color) {
	}

	private static final List<Mark> MARKS = new ArrayList<>();
	/** Wind as velocity added per tick to particles (blocks/tick²). */
	public static volatile double windX, windZ;
	/** Weather: "asche" (falling ash) or "glutsturm" (ember storm); strength eases towards the target. */
	private static String weather = "klar";
	private static float weatherLevel, weatherTarget, weatherRate = 0.02f;
	private static int clock;

	private CrewMarkers() {
	}

	public static void init() {
		ClientCueDispatcher.register("markers", (scene, tick, data) -> {
			MARKS.clear();
			for (JsonElement e : data.getAsJsonArray("markers")) {
				JsonObject m = e.getAsJsonObject();
				MARKS.add(new Mark(m.get("name").getAsString(), m.get("x").getAsDouble(), m.get("y").getAsDouble(), m.get("z").getAsDouble(),
						m.get("color").getAsInt()));
			}
		});
		ClientCueDispatcher.register("weather", (scene, tick, data) -> {
			String type = data.get("type").getAsString();
			float target = type.equals("klar") ? 0 : data.get("strength").getAsFloat();
			if (!type.equals("klar")) {
				weather = type;
			}
			weatherTarget = target;
			weatherRate = 1f / Math.max(1, (float) (data.get("seconds").getAsDouble() * 20));
		});
		ClientCueDispatcher.register("bloodmoon", (scene, tick, data) -> { });
		ClientCueDispatcher.register("wind", (scene, tick, data) -> {
			double deg = data.get("deg").getAsDouble(), s = data.get("strength").getAsDouble();
			// direction the wind blows TO (like Minecraft yaw: 0 = south)
			windX = -Math.sin(Math.toRadians(deg)) * s * 0.006;
			windZ = Math.cos(Math.toRadians(deg)) * s * 0.006;
		});
	}

	private static void tickWeather(Minecraft mc) {
		weatherLevel += Math.max(-weatherRate, Math.min(weatherRate, weatherTarget - weatherLevel));
		if (weatherLevel <= 0.01f || mc.level == null) {
			return;
		}
		var cam = mc.gameRenderer.mainCamera().position();
		var r = mc.level.getRandom();
		int n = Math.round((weather.equals("glutsturm") ? 30 : 40) * weatherLevel);
		for (int i = 0; i < n; i++) {
			double x = cam.x + (r.nextDouble() - 0.5) * 32, y = cam.y + r.nextDouble() * 14 - 3, z = cam.z + (r.nextDouble() - 0.5) * 32;
			if (weather.equals("glutsturm")) {
				mc.level.addParticle(r.nextInt(3) == 0 ? net.minecraft.core.particles.ParticleTypes.SMALL_FLAME : net.minecraft.core.particles.ParticleTypes.ASH,
						x, y, z, windX * 40, 0.01, windZ * 40);
				if (r.nextInt(8) == 0) {
					mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, x, y - 2, z, windX * 30, 0.01, windZ * 30);
				}
			} else {
				mc.level.addParticle(r.nextInt(3) == 0 ? net.minecraft.core.particles.ParticleTypes.WHITE_ASH : net.minecraft.core.particles.ParticleTypes.ASH,
						x, y, z, 0, -0.02, 0);
			}
		}
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		tickWeather(mc);
		if (mc.level == null || mc.player == null || MARKS.isEmpty() || DirectorHotkeys.cleanFeed()) {
			return;
		}
		clock++;
		for (Mark m : MARKS) {
			double dx = m.x - mc.player.getX(), dz = m.z - mc.player.getZ();
			double d2 = dx * dx + dz * dz;
			if (d2 > 48 * 48) {
				continue;
			}
			if (clock % 4 == 0) {
				DustParticleOptions dust = new DustParticleOptions(m.color, 1.4f);
				for (int i = -2; i <= 2; i++) { // an X on the floor
					double o = i * 0.18;
					mc.level.addParticle(dust, m.x + o, m.y + 0.05, m.z + o, 0, 0, 0);
					mc.level.addParticle(dust, m.x + o, m.y + 0.05, m.z - o, 0, 0, 0);
				}
				for (int i = 0; i < 12; i++) { // ring
					double a = i * Math.PI / 6;
					mc.level.addParticle(new DustParticleOptions(m.color, 0.6f), m.x + Math.cos(a) * 0.6, m.y + 0.05, m.z + Math.sin(a) * 0.6, 0, 0, 0);
				}
			}
			if (clock % 6 == 0) { // a glowing point in the middle, visible at night too
				mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.GLOW, m.x, m.y + 0.1, m.z, 0, 0.01, 0);
			}
			if (clock % 10 == 0) { // a short post so it is found from afar
				mc.level.addParticle(new DustParticleOptions(m.color, 0.7f), m.x, m.y + 0.4 + (clock / 10 % 4) * 0.35, m.z, 0, 0, 0);
			}
			if (d2 < 0.8 && clock % 20 == 0) {
				mc.gui.hud.setOverlayMessage(Component.literal("◆ " + m.name), false);
			}
		}
	}
}
