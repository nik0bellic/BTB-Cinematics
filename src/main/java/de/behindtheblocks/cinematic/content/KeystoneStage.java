package de.behindtheblocks.cinematic.content;

import net.minecraft.util.StringRepresentable;

/** Visual stage of a portal keystone (block state {@code stage}). */
public enum KeystoneStage implements StringRepresentable {
	IDLE("idle", 2),
	KEYED("keyed", 7),
	CHARGING("charging", 11),
	ACTIVE("active", 15);

	private final String name;
	public final int light;

	KeystoneStage(String name, int light) {
		this.name = name;
		this.light = light;
	}

	@Override
	public String getSerializedName() {
		return name;
	}
}
