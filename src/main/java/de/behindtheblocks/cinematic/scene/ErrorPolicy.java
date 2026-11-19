package de.behindtheblocks.cinematic.scene;

/** What a running scene does when one of its events throws. Set via {@code "on_error"} in JSON. */
public enum ErrorPolicy {
	/** Log, notify operators, keep playing (default — a broken sound shouldn't ruin a take). */
	CONTINUE,
	/** Log, notify operators, stop the scene immediately. */
	STOP
}
