package de.behindtheblocks.cinematic.client.export;

import com.mojang.blaze3d.platform.NativeImage;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline film export: the game is frozen and advanced exactly one tick every (fps/20) frames; every
 * frame is rendered at a multiple of the window size (≥ the target, e.g. 4K) and saved as a PNG. Nothing
 * depends on how fast the PC renders – the result plays perfectly smooth at 30/60 FPS. At the end
 * ffmpeg (if installed) turns the images into an MP4 at the exact target size.
 *
 * <p>Works in single player / on the host of a LAN world (needs the integrated server to step it).
 */
public final class ExportManager {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/export");

	private static boolean active;
	private static int fps = 60, targetW = 3840, targetH = 2160, scale = 1, totalFrames;
	private static int frame;
	private static int guiScale;
	private static Path dir;
	private static boolean hideGuiBefore;
	private static final AtomicInteger pendingWrites = new AtomicInteger();
	private static String name;

	private ExportManager() {
	}

	public static boolean active() {
		return active;
	}

	/** Simulated time of the current frame (for animations driven by milliseconds). */
	public static long frameTimeMs() {
		return 1_000_000L + Math.round(frame * 1000.0 / fps);
	}

	public static void init() {
		ClientCueDispatcher.register("export_start", (scene, tick, data) -> start(data.get("name").getAsString(), data.get("seconds").getAsDouble(),
				data.get("width").getAsInt(), data.get("height").getAsInt(), data.get("fps").getAsInt(),
				data.has("replay") ? data.get("replay").getAsString() : null, data.has("from") ? data.get("from").getAsDouble() : 0));
		ClientCueDispatcher.register("export_stop", (scene, tick, data) -> finish("abgebrochen"));
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[BTB Export] " + text));
		}
	}

	/** Starts an export; with {@code replay} the take is played from {@code from} seconds in sync with the first frame. */
	public static void start(String exportName, double seconds, int w, int h, int framesPerSecond, String replay, double from) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			say("Export geht nur im Einzelspieler bzw. als Host einer LAN-Welt.");
			return;
		}
		if (active) {
			say("Es läuft schon ein Export.");
			return;
		}
		fps = Math.max(1, framesPerSecond);
		targetW = w;
		targetH = h;
		int ww = Math.max(1, mc.getWindow().getWidth()), wh = Math.max(1, mc.getWindow().getHeight());
		// render at an integer multiple of the window, so the HUD/overlays line up exactly; scaled to the target at the end
		scale = Math.max(1, (int) Math.ceil(Math.max(w / (double) ww, h / (double) wh)));
		guiScale = mc.getWindow().getGuiScale() * scale;
		totalFrames = (int) Math.round(seconds * fps);
		name = exportName;
		dir = mc.gameDirectory.toPath().resolve("btb_export").resolve(exportName);
		try {
			Files.createDirectories(dir);
			try (var s = Files.list(dir)) {
				for (Path p : s.filter(p -> p.getFileName().toString().startsWith("frame_")).toList()) {
					Files.delete(p);
				}
			}
		} catch (IOException e) {
			say("Ordner nicht anlegbar: " + e.getMessage());
			return;
		}
		frame = 0;
		hideGuiBefore = mc.gui.hud.isHidden();
		if (!hideGuiBefore) {
			mc.gui.hud.toggle(); // no hotbar/hand in the film; the cinematic overlays still draw
		}
		// start the take and freeze in the same server step, so frame 0 is exactly the first tick
		server.execute(() -> {
			if (replay != null) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
						String.format(java.util.Locale.ROOT, "btb replay play %s from %.2f", replay, from));
			}
			server.tickRateManager().setFrozen(true);
		});
		active = true;
		say(String.format(java.util.Locale.ROOT, "Exportiere %d Bilder (%.1f s, %d FPS) in %d×%d → %s. Bitte nichts anfassen.",
				totalFrames, seconds, fps, ww * scale, wh * scale, dir));
		LOG.info("Export {} started: {} frames, render {}x{}, target {}x{} @ {} fps", exportName, totalFrames, ww * scale, wh * scale, w, h, fps);
	}

	/** Ticks the client may run in this frame (0 or 1 for fps ≥ 20) – called from the timer mixin. */
	public static int ticksForFrame() {
		return (int) (Math.floorDiv((long) frame * 20, fps) - Math.floorDiv((long) (frame - 1) * 20, fps));
	}

	public static float partialForFrame() {
		return (float) (((long) frame * 20) % fps) / fps;
	}

	public static int renderWidth(int windowWidth) {
		return windowWidth * scale;
	}

	public static int renderHeight(int windowHeight) {
		return windowHeight * scale;
	}

	public static int guiScale() {
		return guiScale;
	}

	/** After a frame was rendered: save it, then prepare the next one (step the server when a tick is due). */
	public static void afterFrame() {
		if (!active) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int index = frame;
		pendingWrites.incrementAndGet();
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
			Path out = dir.resolve(String.format("frame_%06d.png", index));
			net.minecraft.util.Util.ioPool().execute(() -> {
				try (img) {
					img.writeToFile(out);
				} catch (IOException e) {
					LOG.warn("Frame {} not written: {}", index, e.getMessage());
				} finally {
					pendingWrites.decrementAndGet();
				}
			});
		});
		frame++;
		if (frame >= totalFrames) {
			finish(null);
			return;
		}
		if (ticksForFrame() > 0) {
			stepServer(mc);
		}
	}

	private static void stepServer(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			finish("Server weg");
			return;
		}
		int before = server.getTickCount();
		server.execute(() -> server.tickRateManager().stepGameIfPaused(1));
		long deadline = System.currentTimeMillis() + 3000;
		while (server.getTickCount() == before && System.currentTimeMillis() < deadline) {
			Thread.onSpinWait();
		}
	}

	private static void finish(String reason) {
		if (!active) {
			return;
		}
		active = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden() != hideGuiBefore) {
			mc.gui.hud.toggle();
		}
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.tickRateManager().setFrozen(false));
		}
		int frames = frame;
		Path folder = dir;
		say((reason == null ? "Fertig: " : "Gestoppt (" + reason + "): ") + frames + " Bilder in " + folder);
		Thread t = new Thread(() -> encode(folder, frames), "btb-export-encode");
		t.setDaemon(true);
		t.start();
	}

	private static void encode(Path folder, int frames) {
		long wait = System.currentTimeMillis() + 60_000;
		while (pendingWrites.get() > 0 && System.currentTimeMillis() < wait) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				return;
			}
		}
		String ffmpeg = findFfmpeg();
		if (ffmpeg == null) {
			Minecraft.getInstance().execute(() -> say("ffmpeg nicht gefunden – die Einzelbilder liegen in " + folder
					+ ". Mit ffmpeg: ffmpeg -framerate " + fps + " -i frame_%06d.png -vf scale=" + targetW + ":" + targetH + " -c:v libx264 -crf 14 -pix_fmt yuv420p film.mp4"));
			return;
		}
		File out = folder.resolve(name + ".mp4").toFile();
		ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-y", "-framerate", String.valueOf(fps), "-i", folder.resolve("frame_%06d.png").toString(),
				"-vf", "scale=" + targetW + ":" + targetH + ":flags=lanczos", "-c:v", "libx264", "-preset", "slow", "-crf", "14", "-pix_fmt", "yuv420p",
				out.getAbsolutePath());
		pb.redirectErrorStream(true);
		pb.redirectOutput(folder.resolve("ffmpeg.log").toFile());
		try {
			int code = pb.start().waitFor();
			Minecraft.getInstance().execute(() -> say(code == 0 ? "Video fertig: " + out : "ffmpeg-Fehler (siehe ffmpeg.log in " + folder + ")"));
		} catch (IOException | InterruptedException e) {
			Minecraft.getInstance().execute(() -> say("ffmpeg konnte nicht starten: " + e.getMessage()));
		}
	}

	private static String findFfmpeg() {
		for (String c : new String[] {"ffmpeg", "ffmpeg.exe", "C:\\\\ffmpeg\\\\bin\\\\ffmpeg.exe", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg"}) {
			try {
				Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();
				if (p.waitFor() == 0) {
					return c;
				}
			} catch (IOException | InterruptedException ignored) {
				// try the next one
			}
		}
		return null;
	}
}
