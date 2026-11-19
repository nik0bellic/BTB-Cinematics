package de.behindtheblocks.cinematic.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.CommandUtil;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.event.CinematicAction;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.event.builtin.PlayerTargets;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Cinematic sound, server side. Playback happens on clients (cue types {@code sound_play},
 * {@code music}, {@code sound_stop}, {@code reverb}, {@code sound_volume}) so that fades,
 * cross-fades, echoes and reverb can be shaped per frame and stay in sync with the scene clock.
 * Sounds go to all players by default.
 */
public final class SoundManager implements CinematicModule, CommandContributor {
	private CinematicEngine engine;

	@Override
	public String id() {
		return "sound";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> {
			JsonObject stop = new JsonObject();
			stop.addProperty("fade", 10);
			stop.addProperty("scene_only", true);
			engine.cues().queue(m.sceneId(), 0, "sound_stop", stop);
		});
		engine.events().register(BuiltinEvents.simple("sound",
				"Plays a sound. {sound, pos? [x,y,z] | at? player, volume?, pitch?, fade_in?, echo? {count, delay, decay}, players?}",
				this::prepSound));
		engine.events().register(BuiltinEvents.simple("music_play",
				"Starts background music, cross-fading from the current track. {sound, volume?, fade_in? ticks, loop?, players?}",
				p -> {
					JsonObject cue = music(p.requireString("sound"), p.getFloat("volume", 1f), p.getInt("fade_in", 40), p.getBoolean("loop", true));
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> engine.cues().queue(ctx.sceneId(), ctx.tick(), "music", cue, t.all() ? null : t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("music_stop",
				"Fades out the background music. {fade_out? ticks, players?}",
				p -> {
					JsonObject cue = new JsonObject();
					cue.addProperty("stop", true);
					cue.addProperty("fade", p.getInt("fade_out", 40));
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> engine.cues().queue(ctx.sceneId(), ctx.tick(), "music", cue, t.all() ? null : t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("sound_stop",
				"Stops cinematic sounds. {sound? (omit = all), fade? ticks, players?}",
				p -> {
					JsonObject cue = new JsonObject();
					if (p.has("sound")) {
						cue.addProperty("sound", checkSound(p.requireString("sound")).toString());
					}
					cue.addProperty("fade", p.getInt("fade", 10));
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> engine.cues().queue(ctx.sceneId(), ctx.tick(), "sound_stop", cue, t.all() ? null : t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("reverb",
				"Environmental reverb for all new sounds. {preset: off|room|hall|cave|arena|cathedral|nether, mix? 0..1, players?}",
				p -> {
					JsonObject cue = reverb(p.requireString("preset"), p.getFloat("mix", 0.6f));
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> engine.cues().queue(ctx.sceneId(), ctx.tick(), "reverb", cue, t.all() ? null : t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("sound_volume",
				"Master volume of cinematic sounds/music. {volume 0..1, fade? ticks, players?}",
				p -> {
					float v = p.getFloat("volume", 1f);
					if (v < 0 || v > 1) {
						throw new JsonParamException("'volume' must be 0..1");
					}
					JsonObject cue = new JsonObject();
					cue.addProperty("volume", v);
					cue.addProperty("fade", p.getInt("fade", 20));
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> engine.cues().queue(ctx.sceneId(), ctx.tick(), "sound_volume", cue, t.all() ? null : t.resolve(ctx.server()));
				}));
	}

	private static Identifier checkSound(String raw) {
		// your own music (config/btb_cinematic/music, client-side resource pack): musik.<name>
		if (raw.startsWith("musik.") && raw.length() > 6) {
			return Identifier.fromNamespaceAndPath("btb_music", raw.substring(6).toLowerCase(java.util.Locale.ROOT));
		}
		Identifier id = BtbSounds.resolve(raw);
		if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
			throw new JsonParamException("unknown sound '" + raw + "'");
		}
		return id;
	}

	private CinematicAction prepSound(JsonParams p) {
		Identifier id = checkSound(p.requireString("sound"));
		float volume = p.getFloat("volume", 1f);
		float pitch = p.getFloat("pitch", 1f);
		if (volume < 0 || volume > 16 || pitch < 0.5f || pitch > 2f) {
			throw new JsonParamException("volume 0..16, pitch 0.5..2");
		}
		double[] pos = p.has("pos") ? p.requireDoubleArray("pos", 3) : null;
		String at = p.has("at") ? p.requireString("at") : null;
		int fadeIn = p.getInt("fade_in", 0);
		JsonObject echo = p.getObject("echo").orElse(null);
		if (echo != null) {
			JsonParams e = new JsonParams(echo);
			int count = e.getInt("count", 3);
			int delay = e.getInt("delay", 8);
			float decay = e.getFloat("decay", 0.5f);
			if (count < 1 || count > 8 || delay < 1 || delay > 200 || decay <= 0 || decay >= 1) {
				throw new JsonParamException("echo: count 1..8, delay 1..200 ticks, decay 0..1 (exclusive)");
			}
		}
		PlayerTargets t = PlayerTargets.parse(p);
		return ctx -> {
			double[] where = pos;
			if (at != null) {
				ServerPlayer target = ctx.server().getPlayerList().getPlayerByName(at);
				if (target == null) {
					throw new IllegalStateException("player '" + at + "' is not online");
				}
				where = new double[] {target.getX(), target.getEyeY(), target.getZ()};
			}
			engine.cues().queue(ctx.sceneId(), ctx.tick(), "sound_play", sound(id, where, volume, pitch, fadeIn, echo),
					t.all() ? null : t.resolve(ctx.server()));
		};
	}

	static JsonObject sound(Identifier id, double[] pos, float volume, float pitch, int fadeIn, JsonObject echo) {
		JsonObject o = new JsonObject();
		o.addProperty("sound", id.toString());
		o.addProperty("volume", volume);
		o.addProperty("pitch", pitch);
		o.addProperty("fade_in", fadeIn);
		if (pos != null) {
			JsonArray a = new JsonArray();
			for (double d : pos) {
				a.add(d);
			}
			o.add("pos", a);
		}
		if (echo != null) {
			o.add("echo", echo);
		}
		return o;
	}

	static JsonObject music(String raw, float volume, int fadeIn, boolean loop) {
		Identifier id = checkSound(raw);
		JsonObject o = new JsonObject();
		o.addProperty("sound", id.toString());
		o.addProperty("volume", volume);
		o.addProperty("fade", fadeIn);
		o.addProperty("loop", loop);
		return o;
	}

	static JsonObject reverb(String preset, float mix) {
		ReverbPreset rp;
		try {
			rp = ReverbPreset.byId(preset);
		} catch (IllegalArgumentException e) {
			throw new JsonParamException(e.getMessage());
		}
		if (mix < 0 || mix > 1) {
			throw new JsonParamException("'mix' must be 0..1");
		}
		JsonObject o = new JsonObject();
		o.addProperty("preset", rp.id());
		o.addProperty("mix", mix);
		return o;
	}

	/** Plays a sound for everyone from other modules (portal, showcase, destruction). */
	public void play(String sceneId, int tick, Identifier sound, Vec3 pos, float volume, float pitch) {
		engine.cues().queue(sceneId, tick, "sound_play",
				sound(sound, pos == null ? null : new double[] {pos.x, pos.y, pos.z}, volume, pitch, 0, null));
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> sounds = (c, b) -> SharedSuggestionProvider.suggest(Stream.concat(
				BtbSounds.all().stream().map(s -> s.location().getPath()),
				BuiltInRegistries.SOUND_EVENT.keySet().stream().map(Identifier::toString)), b);
		root.then(literal("sound")
				.then(literal("play").then(argument("sound", StringArgumentType.greedyString()).suggests(sounds)
						.executes(c -> playCmd(c, null))))
				.then(literal("at").then(argument("pos", Vec3Argument.vec3())
						.then(argument("sound", StringArgumentType.greedyString()).suggests(sounds)
								.executes(c -> playCmd(c, Vec3Argument.getVec3(c, "pos"))))))
				.then(literal("stop")
						.executes(c -> stopCmd(c, null))
						.then(argument("sound", StringArgumentType.greedyString()).suggests(sounds)
								.executes(c -> stopCmd(c, StringArgumentType.getString(c, "sound")))))
				.then(literal("music")
						.then(literal("stop")
								.executes(c -> musicStop(c, 2))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 120))
										.executes(c -> musicStop(c, DoubleArgumentType.getDouble(c, "seconds")))))
						.then(argument("sound", StringArgumentType.word()).suggests(sounds)
								.executes(c -> musicCmd(c, 2))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 120))
										.executes(c -> musicCmd(c, DoubleArgumentType.getDouble(c, "seconds")))
										// once: plays the track a single time (trailers, songs) instead of looping
										.then(literal("once").executes(c -> musicCmd(c, DoubleArgumentType.getDouble(c, "seconds"), false))))))
				.then(literal("reverb").then(argument("preset", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.of(ReverbPreset.values()).map(ReverbPreset::id), b))
						.executes(c -> reverbCmd(c, 0.6f))
						.then(argument("mix", FloatArgumentType.floatArg(0, 1))
								.executes(c -> reverbCmd(c, FloatArgumentType.getFloat(c, "mix"))))))
				.then(literal("volume").then(argument("volume", FloatArgumentType.floatArg(0, 1)).executes(c -> {
					JsonObject cue = new JsonObject();
					cue.addProperty("volume", FloatArgumentType.getFloat(c, "volume"));
					cue.addProperty("fade", 20);
					engine.cues().queue("", 0, "sound_volume", cue);
					return Feedback.ok(c.getSource(), "Cinematic volume set");
				}))));
	}

	private int playCmd(CommandContext<CommandSourceStack> c, Vec3 pos) {
		// "<sound> [volume] [pitch]" in one greedy argument so sound ids with namespaces tab-complete.
		String[] parts = StringArgumentType.getString(c, "sound").trim().split("\\s+");
		Identifier id = BtbSounds.resolve(parts[0]);
		if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
			return Feedback.fail(c.getSource(), "Unknown sound '" + parts[0] + "'");
		}
		float volume = 1f, pitch = 1f;
		try {
			if (parts.length > 1) {
				volume = Float.parseFloat(parts[1]);
			}
			if (parts.length > 2) {
				pitch = Float.parseFloat(parts[2]);
			}
		} catch (NumberFormatException e) {
			return Feedback.fail(c.getSource(), "Usage: <sound> [volume] [pitch]");
		}
		if (volume < 0 || volume > 16 || pitch < 0.5f || pitch > 2f) {
			return Feedback.fail(c.getSource(), "volume 0..16, pitch 0.5..2");
		}
		play("", 0, id, pos, volume, pitch);
		return Feedback.ok(c.getSource(), "Playing " + id + (pos != null ? String.format(Locale.ROOT, " at %.1f %.1f %.1f", pos.x, pos.y, pos.z) : ""));
	}

	private int stopCmd(CommandContext<CommandSourceStack> c, String sound) {
		JsonObject cue = new JsonObject();
		if (sound != null) {
			Identifier id = BtbSounds.resolve(sound.trim());
			if (id == null) {
				return Feedback.fail(c.getSource(), "Invalid sound id");
			}
			cue.addProperty("sound", id.toString());
		}
		cue.addProperty("fade", 10);
		engine.cues().queue("", 0, "sound_stop", cue);
		return Feedback.ok(c.getSource(), "Sounds stopped");
	}

	private int musicCmd(CommandContext<CommandSourceStack> c, double seconds) {
		return musicCmd(c, seconds, true);
	}

	private int musicCmd(CommandContext<CommandSourceStack> c, double seconds, boolean loop) {
		try {
			JsonObject cue = music(StringArgumentType.getString(c, "sound"), 1f, (int) Math.round(seconds * 20), loop);
			engine.cues().queue("", 0, "music", cue);
			return Feedback.ok(c.getSource(), "Music: " + cue.get("sound").getAsString());
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
	}

	private int musicStop(CommandContext<CommandSourceStack> c, double seconds) {
		JsonObject cue = new JsonObject();
		cue.addProperty("stop", true);
		cue.addProperty("fade", (int) Math.round(seconds * 20));
		engine.cues().queue("", 0, "music", cue);
		return Feedback.ok(c.getSource(), "Music fading out");
	}

	private int reverbCmd(CommandContext<CommandSourceStack> c, float mix) {
		try {
			engine.cues().queue("", 0, "reverb", reverb(StringArgumentType.getString(c, "preset"), mix));
			return Feedback.ok(c.getSource(), "Reverb: " + StringArgumentType.getString(c, "preset"));
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
	}

	/** For tests / other modules. */
	public static List<String> reverbIds() {
		return Stream.of(ReverbPreset.values()).map(ReverbPreset::id).toList();
	}
}
