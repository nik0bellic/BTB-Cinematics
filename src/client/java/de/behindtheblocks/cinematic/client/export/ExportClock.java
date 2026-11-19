package de.behindtheblocks.cinematic.client.export;

/** Wall clock for animations: real time normally, the exact frame time while a film is being exported. */
public final class ExportClock {
	private ExportClock() {
	}

	public static long nowMs() {
		return ExportManager.active() ? ExportManager.frameTimeMs() : System.currentTimeMillis();
	}
}
