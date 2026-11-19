package de.behindtheblocks.cinematic.api;

/**
 * Entry point for other mods. Declare it in your {@code fabric.mod.json}:
 *
 * <pre>{@code
 * "entrypoints": { "btb_cinematic": ["com.example.MyCinematicPlugin"] }
 * }</pre>
 *
 * It is called once during mod initialisation (both sides), after all cinematic modules exist.
 */
@FunctionalInterface
public interface BtbCinematicPlugin {
	void register(BtbCinematicApi api);
}
