package de.behindtheblocks.cinematic.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.event.CinematicEventType;
import de.behindtheblocks.cinematic.scene.LoadProblem;
import de.behindtheblocks.cinematic.scene.PlaybackState;
import de.behindtheblocks.cinematic.scene.SceneDefinition;
import de.behindtheblocks.cinematic.scene.SceneManager;
import de.behindtheblocks.cinematic.scene.ScenePlayback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Root of the {@code /btb} command tree. All sub-commands require game-master permission
 * (operator level 2), the same level vanilla uses for /tp or /effect.
 *
 * <p>Modules attach their own branches via {@link CommandContributor}.
 */
public final class BtbCommands {
	private BtbCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
			CinematicEngine engine, List<CommandContributor> contributors) {
		SceneManager scenes = engine.scenes();
		SuggestionProvider<CommandSourceStack> allScenes =
				(ctx, b) -> SharedSuggestionProvider.suggest(scenes.sceneIds(), b);
		SuggestionProvider<CommandSourceStack> runningScenes =
				(ctx, b) -> SharedSuggestionProvider.suggest(scenes.activeIds(), b);

		LiteralArgumentBuilder<CommandSourceStack> root = literal("btb")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> help(ctx.getSource()));

		root.then(literal("help").executes(ctx -> help(ctx.getSource())));

		root.then(literal("reload").executes(ctx -> reload(ctx.getSource(), scenes)));

		root.then(literal("events").executes(ctx -> {
			Feedback.ok(ctx.getSource(), "Available timeline event types:");
			for (CinematicEventType t : engine.events().all()) {
				Feedback.info(ctx.getSource(), " • " + t.id() + " — " + t.description());
			}
			return engine.events().all().size();
		}));

		root.then(literal("scene")
				.then(literal("list").executes(ctx -> list(ctx.getSource(), scenes)))
				.then(literal("status").executes(ctx -> status(ctx.getSource(), scenes)))
				.then(literal("reload").executes(ctx -> reload(ctx.getSource(), scenes)))
				.then(literal("problems").executes(ctx -> problems(ctx.getSource(), scenes)))
				.then(literal("start").then(argument("id", StringArgumentType.word()).suggests(allScenes)
						.executes(ctx -> outcome(ctx, scenes::start))
						// jump in: /btb scene start <id> from <seconds>
						.then(literal("from").then(argument("seconds", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 7200))
								.executes(ctx -> outcome(ctx, id -> scenes.start(id,
										(int) Math.round(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "seconds") * 20))))))))
				.then(literal("restart").then(argument("id", StringArgumentType.word()).suggests(allScenes)
						.executes(ctx -> outcome(ctx, scenes::restart))
						.then(literal("from").then(argument("seconds", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 7200))
								.executes(ctx -> {
									String id = StringArgumentType.getString(ctx, "id");
									if (scenes.isRunning(id)) {
										scenes.stop(id);
									}
									return outcome(ctx, x -> scenes.start(x,
											(int) Math.round(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "seconds") * 20)));
								})))))
				.then(literal("play").then(argument("id", StringArgumentType.word()).suggests(allScenes)
						.executes(ctx -> outcome(ctx, scenes::restart))))
				.then(literal("reset").then(argument("id", StringArgumentType.word()).suggests(allScenes)
						.executes(ctx -> outcome(ctx, scenes::reset))))
				.then(literal("info").then(argument("id", StringArgumentType.word()).suggests(allScenes)
						.executes(ctx -> info(ctx.getSource(), scenes, StringArgumentType.getString(ctx, "id")))))
				.then(literal("stop")
						.executes(ctx -> {
							int n = scenes.stopAll();
							return n == 0 ? Feedback.fail(ctx.getSource(), "No scene is running")
									: Feedback.ok(ctx.getSource(), "Stopped " + n + " scene(s)");
						})
						.then(argument("id", StringArgumentType.word()).suggests(runningScenes)
								.executes(ctx -> outcome(ctx, scenes::stop))))
				.then(literal("pause")
						.executes(ctx -> forAll(ctx.getSource(), scenes, scenes::pause, PlaybackState.RUNNING, "paused"))
						.then(argument("id", StringArgumentType.word()).suggests(runningScenes)
								.executes(ctx -> outcome(ctx, scenes::pause))))
				.then(literal("resume")
						.executes(ctx -> forAll(ctx.getSource(), scenes, scenes::resume, PlaybackState.PAUSED, "resumed"))
						.then(argument("id", StringArgumentType.word()).suggests(runningScenes)
								.executes(ctx -> outcome(ctx, scenes::resume)))));

		for (CommandContributor c : contributors) {
			c.contributeCommands(root, buildContext);
		}
		dispatcher.register(root);
	}

	private static int outcome(CommandContext<CommandSourceStack> ctx, Function<String, SceneManager.Outcome> op) {
		SceneManager.Outcome o = op.apply(StringArgumentType.getString(ctx, "id"));
		if (!o.success() && o.message().startsWith("Unknown scene")) {
			String id = StringArgumentType.getString(ctx, "id");
			return Feedback.fail(ctx.getSource(), o.message() + de.behindtheblocks.cinematic.studio.Suggest.hint(id,
					de.behindtheblocks.cinematic.BtbCinematic.engine().scenes().sceneIds()));
		}
		return o.success() ? Feedback.ok(ctx.getSource(), o.message()) : Feedback.fail(ctx.getSource(), o.message());
	}

	private static int forAll(CommandSourceStack source, SceneManager scenes,
			Function<String, SceneManager.Outcome> op, PlaybackState required, String verb) {
		List<String> ids = scenes.activePlaybacks().stream()
				.filter(p -> p.state() == required).map(p -> p.scene().id()).toList();
		if (ids.isEmpty()) {
			return Feedback.fail(source, "No scene can be " + verb);
		}
		ids.forEach(op::apply);
		return Feedback.ok(source, "Scene(s) " + verb + ": " + String.join(", ", ids));
	}

	private static int help(CommandSourceStack s) {
		Feedback.ok(s, "Behind The Blocks – Cinematic Engine");
		Feedback.info(s, "/btb scene list|status|info|start|restart|stop|pause|resume|reset|reload|problems");
		Feedback.info(s, "/btb events — list timeline event types");
		Feedback.info(s, "/btb reload — reload scene files from config/btb_cinematic/scenes");
		return 1;
	}

	private static int reload(CommandSourceStack s, SceneManager scenes) {
		int n = scenes.reload();
		int problems = scenes.problems().values().stream().mapToInt(List::size).sum();
		Feedback.ok(s, "Loaded " + n + " scene(s)" + (problems > 0 ? ", " + problems + " problem(s) — /btb scene problems" : ""));
		return n;
	}

	private static int list(CommandSourceStack s, SceneManager scenes) {
		if (scenes.sceneIds().isEmpty()) {
			return Feedback.fail(s, "No scenes loaded. Put JSON files into " + SceneManager.sceneDirectory());
		}
		Feedback.ok(s, scenes.sceneIds().size() + " scene(s):");
		for (String id : scenes.sceneIds()) {
			SceneDefinition d = scenes.definition(id).orElseThrow();
			String running = scenes.activeIds().contains(id) ? " ▶" : "";
			Feedback.info(s, " • " + id + " (" + d.duration() + "t, " + d.events().size() + " events)" + running);
		}
		return scenes.sceneIds().size();
	}

	private static int status(CommandSourceStack s, SceneManager scenes) {
		if (scenes.activePlaybacks().isEmpty()) {
			return Feedback.ok(s, "No scene is running");
		}
		for (ScenePlayback p : scenes.activePlaybacks()) {
			Feedback.info(s, " • " + p.scene().id() + ": " + p.state() + " tick " + p.tick() + "/" + p.scene().duration()
					+ (p.loops() > 0 ? " loop " + p.loops() : "") + (p.failures() > 0 ? " failures " + p.failures() : ""));
		}
		return scenes.activePlaybacks().size();
	}

	private static int info(CommandSourceStack s, SceneManager scenes, String id) {
		SceneDefinition d = scenes.definition(id).orElse(null);
		if (d == null) {
			return Feedback.fail(s, "Unknown scene '" + id + "'");
		}
		Feedback.ok(s, d.id() + (d.description().isEmpty() ? "" : " — " + d.description()));
		Feedback.info(s, "file " + d.source() + ", " + d.duration() + " ticks (" + String.format("%.1f", d.duration() / 20.0)
				+ " s), loop " + d.loop() + ", on_error " + d.errorPolicy().name().toLowerCase());
		d.events().forEach(e -> Feedback.info(s, "  t=" + e.time() + "  " + e.type()));
		d.problems().forEach(p -> Feedback.info(s, "  ⚠ " + p.message()));
		return 1;
	}

	private static int problems(CommandSourceStack s, SceneManager scenes) {
		if (scenes.problems().isEmpty()) {
			return Feedback.ok(s, "All scene files loaded without problems");
		}
		for (Map.Entry<String, List<LoadProblem>> e : scenes.problems().entrySet()) {
			Feedback.ok(s, e.getKey() + ":");
			e.getValue().forEach(p -> Feedback.info(s, "  " + p));
		}
		return scenes.problems().size();
	}
}
