package de.behindtheblocks.cinematic.event;

/** Executable, pre-validated form of a timeline event. */
@FunctionalInterface
public interface CinematicAction {
	void execute(EventContext context) throws Exception;
}
