package de.behindtheblocks.cinematic.client.director;

import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.client.obs.ObsBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Picture in picture on the recording client: tells OBS to show the second client (the actor's view)
 * in a corner and draws a thin frame and the actor's name around exactly that spot. On the viewing
 * client (tag {@code pip}) it switches the clean feed on, so the inset shows the pure view.
 */
public final class PipOverlay {
	private static boolean on;
	private static String corner = "tr";
	private static float size = 0.3f;
	private static String label = "";
	private static long since;

	private PipOverlay() {
	}

	public static void init() {
		ClientCueDispatcher.register("pip", (scene, tick, data) -> {
			on = data.get("on").getAsBoolean();
			if (data.has("corner")) {
				corner = data.get("corner").getAsString();
			}
			if (data.has("size")) {
				size = data.get("size").getAsFloat();
			}
			label = data.has("label") ? data.get("label").getAsString() : "";
			since = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
			ObsBridge.pip(on, corner, size);
		});
		ClientCueDispatcher.register("pip_view", (scene, tick, data) -> DirectorHotkeys.setCleanFeed(data.get("on").getAsBoolean()));
	}

	public static void render(GuiGraphicsExtractor g) {
		if (!on || !ObsBridge.enabled()) {
			return;
		}
		int w = g.guiWidth(), h = g.guiHeight();
		double[] r = ObsBridge.pipRect(corner, size, w, h);
		int x = (int) Math.round(r[0]), y = (int) Math.round(r[1]), pw = (int) Math.round(r[2]), ph = (int) Math.round(r[3]);
		float a = Math.min(1f, (de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() - since) / 300f);
		int line = ((int) (a * 0xC0) << 24) | 0xF0E6D8, shadow = ((int) (a * 0x70) << 24);
		// soft shadow, then a thin frame just outside the inset (OBS lays the actor's view inside it)
		g.fill(x - 3, y - 3, x + pw + 3, y + ph + 3, shadow);
		g.fill(x - 1, y - 1, x + pw + 1, y, line);
		g.fill(x - 1, y + ph, x + pw + 1, y + ph + 1, line);
		g.fill(x - 1, y, x, y + ph, line);
		g.fill(x + pw, y, x + pw + 1, y + ph, line);
		if (!label.isEmpty()) {
			var font = Minecraft.getInstance().font;
			int ty = corner.startsWith("t") ? y + ph + 4 : y - 11;
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("◉ " + label), x, ty, ((int) (a * 0xE0) << 24) | 0xF0E6D8, true);
		}
	}
}
