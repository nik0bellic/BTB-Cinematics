package de.behindtheblocks.cinematic.effects;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.CommandUtil;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.event.builtin.PlayerTargets;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Screen effects, server side: validates effect instructions (from commands or the timeline)
 * and sends them to clients as {@code effect} cues. Rendering happens in the client's
 * {@code ScreenEffectRenderer}. Effects started by a scene are cleared when the scene is reset.
 *
 * <p>Command effects go to <b>all players</b> by default (the whole set sees the same image);
 * scene events can restrict them with {@code players}.
 */
public final class EffectManager implements CinematicModule, CommandContributor {
	private CinematicEngine engine;

	@Override
	public String id() {
		return "effects";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> {
			JsonObject clear = new EffectSpec(EffectSpec.Kind.CLEAR, 0, 0, 1, 10, 0, 0, "all").toJson();
			engine.cues().queue(m.sceneId(), 0, "effect", clear);
		});
		register("screen_fade", EffectSpec.Kind.FADE,
				"Full-screen fade. {color, duration (in ticks), hold? (-1 = stay), out?, intensity?, players?}");
		register("screen_flash", EffectSpec.Kind.FLASH, "Light flash. {color?, duration?, intensity?, players?}");
		register("vignette", EffectSpec.Kind.VIGNETTE, "Vignette. {intensity 0..1, color?, in?, hold?, out?, players?}");
		register("screen_tint", EffectSpec.Kind.TINT,
				"Color overlay / cross-fade. {color, color2?, intensity (alpha), in?, hold?, out?, players?}");
		register("blur", EffectSpec.Kind.BLUR, "Blurs the image. {intensity 0..10, in?, hold?, out?, players?}");
		register("letterbox", EffectSpec.Kind.LETTERBOX, "Cinema bars. {intensity 0..0.4 (bar height), in?, hold?, out?, players?}");
		register("transition", EffectSpec.Kind.TRANSITION,
				"Shaped transition. {style: wipe_left|wipe_right|wipe_up|wipe_down|iris_close|iris_open|bars_close|bars_open, color?, in, hold?, out?, players?}");
		register("lens_flare", EffectSpec.Kind.FLARE,
				"Lens flare on a world point (the glowing building on the horizon). {pos [x,y,z], color?, intensity 0..1, in?, hold?, out?, players?}");
		register("effects_clear", EffectSpec.Kind.CLEAR, "Fades effects out. {style? (all|fade|vignette|...), duration?, players?}");
	}

	private void register(String type, EffectSpec.Kind kind, String description) {
		engine.events().register(BuiltinEvents.simple(type, description, p -> {
			EffectSpec spec = EffectSpec.fromJson(kind, p);
			PlayerTargets targets = PlayerTargets.parse(p);
			return ctx -> send(ctx.sceneId(), ctx.tick(), spec, targets.all() ? null : targets.resolve(ctx.server()));
		}));
	}

	/** @param players recipients or null for everyone */
	public void send(String sceneId, int tick, EffectSpec spec, Collection<ServerPlayer> players) {
		engine.cues().queue(sceneId, tick, "effect", spec.toJson(), players);
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> colors = (c, b) -> SharedSuggestionProvider.suggest(Colors.names(), b);
		LiteralArgumentBuilder<CommandSourceStack> fx = literal("effects");

		// fade <color> <seconds> [hold] [out]
		fx.then(literal("fade").then(argument("color", StringArgumentType.word()).suggests(colors)
				.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600))
						.executes(c -> fade(c, false, false))
						.then(argument("hold", DoubleArgumentType.doubleArg(0, 3600))
								.executes(c -> fade(c, true, false))
								.then(argument("out", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> fade(c, true, true)))))));
		fx.then(literal("fadeout")
				.executes(c -> clear(c, "fade", 1))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600))
						.executes(c -> clear(c, "fade", DoubleArgumentType.getDouble(c, "seconds")))));
		fx.then(literal("flash")
				.executes(c -> simple(c, EffectSpec.Kind.FLASH, "white", 1f, 1, 0, 12))
				.then(argument("color", StringArgumentType.word()).suggests(colors)
						.executes(c -> simple(c, EffectSpec.Kind.FLASH, color(c), 1f, 1, 0, 12))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 60)).executes(c ->
								simple(c, EffectSpec.Kind.FLASH, color(c), 1f, 1, 0, CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")))))));
		fx.then(literal("shake").then(argument("intensity", FloatArgumentType.floatArg(0, 5))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 600)).executes(c -> {
					CameraManager cam = engine.module(CameraManager.class).orElseThrow();
					cam.shake("", 0, FloatArgumentType.getFloat(c, "intensity"),
							CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")), 1f, all(c));
					return Feedback.ok(c.getSource(), "Camera shake");
				}))));
		fx.then(slot("vignette", EffectSpec.Kind.VIGNETTE, 0, 1, "black", colors));
		fx.then(slot("blur", EffectSpec.Kind.BLUR, 0, 10, "black", colors));
		fx.then(slot("letterbox", EffectSpec.Kind.LETTERBOX, 0, 0.4f, "black", colors));
		fx.then(literal("tint")
				.then(literal("off").executes(c -> clear(c, "tint", 1))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> clear(c, "tint", DoubleArgumentType.getDouble(c, "seconds")))))
				.then(argument("color", StringArgumentType.word()).suggests(colors)
						.then(argument("alpha", FloatArgumentType.floatArg(0, 1))
								.executes(c -> simple(c, EffectSpec.Kind.TINT, color(c), FloatArgumentType.getFloat(c, "alpha"), 20, -1, 0))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600)).executes(c ->
										simple(c, EffectSpec.Kind.TINT, color(c), FloatArgumentType.getFloat(c, "alpha"),
												CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")), -1, 0))
										.then(argument("dauer", DoubleArgumentType.doubleArg(0, 3600)).executes(c ->
												simple(c, EffectSpec.Kind.TINT, color(c), FloatArgumentType.getFloat(c, "alpha"),
														CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")), -1, 0)))))));
		fx.then(literal("transition").then(argument("style", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(EffectSpec.TRANSITIONS, b))
				.then(argument("color", StringArgumentType.word()).suggests(colors)
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 600)).executes(c -> {
							String style = StringArgumentType.getString(c, "style");
							if (!EffectSpec.TRANSITIONS.contains(style)) {
								return Feedback.fail(c.getSource(), "Unknown transition style");
							}
							int in = CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"));
							send("", 0, new EffectSpec(EffectSpec.Kind.TRANSITION, Colors.parse(color(c)), 0, 1, in, -1, 0, style), null);
							return Feedback.ok(c.getSource(), "Transition " + style);
						})))));
		fx.then(literal("flare")
				.then(literal("off").executes(c -> clear(c, "flare", 1)))
				.then(argument("pos", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
						.executes(c -> flare(c, "#FFE0B0", 0.9f))
						.then(argument("color", StringArgumentType.word()).suggests(colors)
								.executes(c -> flare(c, color(c), 0.9f))
								.then(argument("intensity", FloatArgumentType.floatArg(0, 1))
										.executes(c -> flare(c, color(c), FloatArgumentType.getFloat(c, "intensity")))))));
		// look: film grain and heat shimmer (stay until off), rack focus, muffled hearing
		fx.then(slot("grain", EffectSpec.Kind.GRAIN, 0, 1, "black", colors));
		fx.then(slot("haze", EffectSpec.Kind.HAZE, 0, 1, "black", colors));
		// colour grading: lut <look|cube file> [strength] [seconds] · lut off
		fx.then(literal("lut")
				.then(literal("off").executes(c -> clear(c, "lut", 1))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> clear(c, "lut", DoubleArgumentType.getDouble(c, "seconds")))))
				.then(argument("look", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("nether", "film", "kalt", "bleach", "nacht", "glut"), b))
						.executes(c -> lut(c, 0.8f, 1))
						.then(argument("strength", FloatArgumentType.floatArg(0, 1)).executes(c -> lut(c, FloatArgumentType.getFloat(c, "strength"), 1))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600)).executes(c -> lut(c,
										FloatArgumentType.getFloat(c, "strength"), DoubleArgumentType.getDouble(c, "seconds")))
										.then(argument("dauer", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> lut(c,
												FloatArgumentType.getFloat(c, "strength"), DoubleArgumentType.getDouble(c, "seconds"))))))));
		fx.then(slot("anamorph", EffectSpec.Kind.ANAMORPH, 0, 1, "black", colors));
		fx.then(slot("flashback", EffectSpec.Kind.FLASHBACK, 0, 1, "black", colors));
		// depth of field: dof auto [strength] [seconds] · dof <blocks> [strength] [seconds] · dof off
		fx.then(literal("dof")
				.then(literal("off").executes(c -> clear(c, "dof", 1))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> clear(c, "dof", DoubleArgumentType.getDouble(c, "seconds")))))
				.then(literal("auto").executes(c -> dof(c, "auto", 0.6f, 1))
						.then(argument("strength", FloatArgumentType.floatArg(0, 1)).executes(c -> dof(c, "auto", FloatArgumentType.getFloat(c, "strength"), 1))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600)).executes(c -> dof(c, "auto",
										FloatArgumentType.getFloat(c, "strength"), DoubleArgumentType.getDouble(c, "seconds")))
										.then(argument("dauer", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> dof(c, "auto",
												FloatArgumentType.getFloat(c, "strength"), DoubleArgumentType.getDouble(c, "seconds")))))))
				.then(argument("blocks", DoubleArgumentType.doubleArg(1, 80))
						.executes(c -> dof(c, String.valueOf(DoubleArgumentType.getDouble(c, "blocks")), 0.6f, 1))
						.then(argument("strength", FloatArgumentType.floatArg(0, 1)).executes(c -> dof(c,
								String.valueOf(DoubleArgumentType.getDouble(c, "blocks")), FloatArgumentType.getFloat(c, "strength"), 1))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600)).executes(c -> dof(c,
										String.valueOf(DoubleArgumentType.getDouble(c, "blocks")), FloatArgumentType.getFloat(c, "strength"),
										DoubleArgumentType.getDouble(c, "seconds")))
										.then(argument("dauer", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> dof(c,
												String.valueOf(DoubleArgumentType.getDouble(c, "blocks")), FloatArgumentType.getFloat(c, "strength"),
												DoubleArgumentType.getDouble(c, "seconds"))))))));
		// cinema formats: format 2.39 | 2 | 1.85 | 4x3 | 1x1 | off [seconds]
		fx.then(literal("format")
				.then(literal("off").executes(c -> clear(c, "letterbox", 1)))
				.then(argument("ratio", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("2.39", "2", "1.85", "4x3", "1x1"), b))
						.executes(c -> format(c, 1))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 60)).executes(c -> format(c, DoubleArgumentType.getDouble(c, "seconds")))
								.then(argument("dauer", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> format(c, DoubleArgumentType.getDouble(c, "seconds")))))));
		fx.then(literal("focus")
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.1, 60))
						.executes(c -> focus(c, 6f, false))
						.then(argument("strength", FloatArgumentType.floatArg(1, 10))
								.executes(c -> focus(c, FloatArgumentType.getFloat(c, "strength"), false))
								.then(literal("in").executes(c -> focus(c, FloatArgumentType.getFloat(c, "strength"), false)))
								.then(literal("out").executes(c -> focus(c, FloatArgumentType.getFloat(c, "strength"), true))))));
		fx.then(literal("muffle")
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.1, 60))
						.executes(c -> muffle(c, 0.9f))
						.then(argument("strength", FloatArgumentType.floatArg(0, 1))
								.executes(c -> muffle(c, FloatArgumentType.getFloat(c, "strength"))))));
		fx.then(literal("clear")
				.executes(c -> clear(c, "all", 0.5))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600))
						.executes(c -> clear(c, "all", DoubleArgumentType.getDouble(c, "seconds")))));
		root.then(fx);
	}

	private LiteralArgumentBuilder<CommandSourceStack> slot(String name, EffectSpec.Kind kind, float min, float max, String color,
			SuggestionProvider<CommandSourceStack> colors) {
		return literal(name)
				.then(literal("off").executes(c -> clear(c, name, 1))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> clear(c, name, DoubleArgumentType.getDouble(c, "seconds")))))
				.then(argument("intensity", FloatArgumentType.floatArg(min, max))
						.executes(c -> simple(c, kind, color, FloatArgumentType.getFloat(c, "intensity"), 20, -1, 0))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 3600))
								.executes(c -> simple(c, kind, color, FloatArgumentType.getFloat(c, "intensity"),
										CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")), -1, 0))
								// optional duration: the effect fades out again on its own after <dauer> seconds
								.then(argument("dauer", DoubleArgumentType.doubleArg(0, 3600))
										.executes(c -> simple(c, kind, color, FloatArgumentType.getFloat(c, "intensity"),
												CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")), -1, 0)))));
	}

	private static String color(CommandContext<CommandSourceStack> c) {
		return StringArgumentType.getString(c, "color");
	}

	private static Collection<ServerPlayer> all(CommandContext<CommandSourceStack> c) {
		return List.copyOf(c.getSource().getServer().getPlayerList().getPlayers());
	}

	private int fade(CommandContext<CommandSourceStack> c, boolean hasHold, boolean hasOut) {
		int in = (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20);
		int hold = hasHold ? (int) Math.round(DoubleArgumentType.getDouble(c, "hold") * 20) : -1;
		int out = hasOut ? (int) Math.round(DoubleArgumentType.getDouble(c, "out") * 20) : 0;
		return simple(c, EffectSpec.Kind.FADE, color(c), 1f, in, hold, out);
	}

	/** Optional "dauer" (duration) in ticks, or -1 (the effect stays until switched off). */
	private static int duration(CommandContext<CommandSourceStack> c) {
		try {
			return CommandUtil.ticks(DoubleArgumentType.getDouble(c, "dauer"));
		} catch (IllegalArgumentException e) {
			return -1;
		}
	}

	/** EffectSpec with the optional duration applied: hold for "dauer", then fade out as long as it faded in. */
	private static EffectSpec timed(CommandContext<CommandSourceStack> c, EffectSpec.Kind kind, int argb, float intensity, int in, String style) {
		int d = duration(c);
		return new EffectSpec(kind, argb, argb, intensity, in, d, d >= 0 ? Math.max(10, in) : 0, style);
	}

	private int simple(CommandContext<CommandSourceStack> c, EffectSpec.Kind kind, String color, float intensity, int in, int hold, int out) {
		if (hold < 0 && duration(c) >= 0) {
			hold = duration(c);
			out = Math.max(10, in);
		}
		int argb;
		try {
			argb = Colors.parse(color);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		send("", 0, new EffectSpec(kind, argb, argb, intensity, in, hold, out, ""), null);
		return Feedback.ok(c.getSource(), "Effect " + kind.id() + " sent");
	}

	private int flare(CommandContext<CommandSourceStack> c, String color, float intensity) {
		net.minecraft.world.phys.Vec3 p = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "pos");
		int argb;
		try {
			argb = Colors.parse(color);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		send("", 0, new EffectSpec(EffectSpec.Kind.FLARE, argb, argb, intensity, 30, -1, 0, p.x + "," + p.y + "," + p.z), null);
		return Feedback.ok(c.getSource(), "Lens flare on " + (int) p.x + " " + (int) p.y + " " + (int) p.z);
	}

	/**
	 * Rack focus: "in" = the picture starts blurred and pulls sharp over the given seconds,
	 * "out" = it slides out of focus and stays soft (until /btb effects blur off).
	 */
	private int focus(CommandContext<CommandSourceStack> c, float strength, boolean out) {
		int t = CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"));
		EffectSpec spec = out ? new EffectSpec(EffectSpec.Kind.BLUR, 0xFF000000, 0xFF000000, strength, t, -1, 0, "")
				: new EffectSpec(EffectSpec.Kind.BLUR, 0xFF000000, 0xFF000000, strength, 0, 0, t, "");
		send("", 0, spec, null);
		return Feedback.ok(c.getSource(), out ? "Focus out" : "Focus pull");
	}

	private int lut(CommandContext<CommandSourceStack> c, float strength, double seconds) {
		String look = StringArgumentType.getString(c, "look");
		send("", 0, timed(c, EffectSpec.Kind.LUT, 0xFF000000, strength, CommandUtil.ticks(seconds), look), null);
		return Feedback.ok(c.getSource(), "Farbkorrektur „" + look + "“ (eigene .cube-Dateien: config/btb_cinematic/luts/)");
	}

	private int dof(CommandContext<CommandSourceStack> c, String focus, float strength, double seconds) {
		send("", 0, timed(c, EffectSpec.Kind.DOF, 0xFF000000, strength, CommandUtil.ticks(seconds), focus), null);
		return Feedback.ok(c.getSource(), "Tiefenschärfe " + (focus.equals("auto") ? "folgt dem Blick" : "auf " + focus + " Blöcke"));
	}

	private int format(CommandContext<CommandSourceStack> c, double seconds) {
		String r = StringArgumentType.getString(c, "ratio").replace('x', ':').replace('/', ':');
		double ratio;
		try {
			ratio = r.contains(":") ? Double.parseDouble(r.split(":")[0]) / Double.parseDouble(r.split(":")[1]) : Double.parseDouble(r);
		} catch (RuntimeException e) {
			return Feedback.fail(c.getSource(), "Format z. B. 2.39, 1.85, 4x3, 1x1");
		}
		if (ratio < 0.5 || ratio > 3.5) {
			return Feedback.fail(c.getSource(), "Format zwischen 1:2 und 3.5:1");
		}
		send("", 0, timed(c, EffectSpec.Kind.LETTERBOX, 0xFF000000, 0.4f, CommandUtil.ticks(seconds), "ratio:" + ratio), null);
		return Feedback.ok(c.getSource(), "Bildformat " + r);
	}

	/** Muffled hearing (like after an explosion): drops in at once, clears over the given seconds. */
	private int muffle(CommandContext<CommandSourceStack> c, float strength) {
		int t = CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"));
		send("", 0, new EffectSpec(EffectSpec.Kind.MUFFLE, 0xFF000000, 0xFF000000, strength, 2, t / 3, t - t / 3, ""), null);
		return Feedback.ok(c.getSource(), "Muffled hearing");
	}

	private int clear(CommandContext<CommandSourceStack> c, String style, double seconds) {
		send("", 0, new EffectSpec(EffectSpec.Kind.CLEAR, 0, 0, 1, (int) Math.round(seconds * 20), 0, 0, style), null);
		return Feedback.ok(c.getSource(), "Cleared " + style);
	}

	/** Validates JSON effect params for other modules (e.g. portal transitions). */
	public static EffectSpec parse(EffectSpec.Kind kind, JsonObject json) {
		return EffectSpec.fromJson(kind, new JsonParams(json));
	}
}
