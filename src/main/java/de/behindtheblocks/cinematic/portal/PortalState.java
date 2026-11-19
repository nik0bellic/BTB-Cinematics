package de.behindtheblocks.cinematic.portal;

/** Lifecycle of a cinematic portal. */
public enum PortalState {
	/** Dormant frame, no surface. */
	INACTIVE,
	/** The Nether Key was recognised in the keystone. */
	KEY_DETECTED,
	/** Frame runes ignite one by one, energy gathers. */
	CHARGING,
	/** Surface has opened (flash, sound) and is settling. */
	ACTIVE,
	/** Players walking in are transported. */
	ENTERABLE
}
