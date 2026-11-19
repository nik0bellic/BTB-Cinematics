package de.behindtheblocks.cinematic.sound;

import de.behindtheblocks.cinematic.BtbCinematic;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The mod's own sound events. Audio files live in {@code assets/btb_cinematic/sounds/*.ogg} and are
 * declared in {@code assets/btb_cinematic/sounds.json}; a resource pack can replace any of them.
 * All effects are original, procedurally synthesised ({@code tools/generate_sounds.py}).
 */
public final class BtbSounds {
	private static final List<SoundEvent> ALL = new ArrayList<>();

	public static final SoundEvent PORTAL_CHARGE = register("portal_charge");
	public static final SoundEvent PORTAL_OPEN = register("portal_open");
	/** Alias for {@link #PORTAL_OPEN}, so {@code /btb sound play portal} works. */
	public static final SoundEvent PORTAL = register("portal");
	public static final SoundEvent PORTAL_ENTER = register("portal_enter");
	public static final SoundEvent HEART_PULSE = register("heart_pulse");
	public static final SoundEvent HEART_REVEAL = register("heart_reveal");
	public static final SoundEvent IMPACT = register("impact");
	public static final SoundEvent RUMBLE = register("rumble");
	public static final SoundEvent WHOOSH = register("whoosh");
	public static final SoundEvent KEY_INSERT = register("key_insert");
	public static final SoundEvent CRYSTAL_SHIMMER = register("crystal_shimmer");
	public static final SoundEvent COLLAPSE = register("collapse");
	public static final SoundEvent TITLE_HIT = register("title_hit");
	public static final SoundEvent NETHER_DRONE = register("nether_drone");
	public static final SoundEvent EAR_RING = register("ear_ring");
	public static final SoundEvent WHITE_TONE = register("white_tone");
	public static final SoundEvent CLAP = register("clap");
	public static final SoundEvent FLARE_IGNITE = register("flare_ignite");

	private BtbSounds() {
	}

	private static SoundEvent register(String name) {
		Identifier id = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, name);
		SoundEvent event = Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
		ALL.add(event);
		return event;
	}

	/** Forces class loading (registration) during mod init. */
	public static void init() {
	}

	public static List<SoundEvent> all() {
		return Collections.unmodifiableList(ALL);
	}

	/**
	 * Resolves a sound id written by a user: {@code btb:x} and bare {@code x} map to this mod's
	 * namespace when such a sound exists, otherwise to {@code minecraft:x}.
	 */
	public static Identifier resolve(String raw) {
		String s = raw.startsWith("btb:") ? BtbCinematic.MOD_ID + ":" + raw.substring(4) : raw;
		if (!s.contains(":")) {
			Identifier own = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, s);
			if (BuiltInRegistries.SOUND_EVENT.containsKey(own)) {
				return own;
			}
			return Identifier.withDefaultNamespace(s);
		}
		return Identifier.tryParse(s);
	}
}
