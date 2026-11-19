package de.behindtheblocks.cinematic.scene;

/** A warning or error found while loading a scene file. */
public record LoadProblem(Severity severity, String message) {
	public enum Severity {
		/** The scene loaded, but something was skipped. */
		WARNING,
		/** The scene could not be loaded at all. */
		ERROR
	}

	public static LoadProblem warning(String message) {
		return new LoadProblem(Severity.WARNING, message);
	}

	public static LoadProblem error(String message) {
		return new LoadProblem(Severity.ERROR, message);
	}

	@Override
	public String toString() {
		return severity + ": " + message;
	}
}
