package de.behindtheblocks.cinematic.content;

import net.minecraft.util.StringRepresentable;

/** Look of a portal: rift texture, frame block, particles, light and sound colour. */
public enum PortalStyle implements StringRepresentable {
	/** Crimson-violet swirl, blackstone and gold frame. */
	NETHER("nether"),
	/** Radiant white light with gold and ice-blue streaks, quartz frame — the "epic" portal. */
	WHITE("white");

	private final String name;

	PortalStyle(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	public static PortalStyle byName(String name) {
		for (PortalStyle s : values()) {
			if (s.name.equals(name)) {
				return s;
			}
		}
		return null;
	}
}
