package de.behindtheblocks.cinematic.director;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.destruction.DestructionManager;
import de.behindtheblocks.cinematic.effects.EffectManager;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.hud.HudManager;
import de.behindtheblocks.cinematic.portal.PortalManager;
import de.behindtheblocks.cinematic.scene.ScenePlayback;
import de.behindtheblocks.cinematic.showcase.ShowcaseManager;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Director Mode, server side.
 *
 * <p>{@code /btb director} sends the current production state (scenes, cameras, showcases,
 * portals, destruction areas, HUD texts, sounds, players) to the requesting operator, whose client
 * opens the Director screen. Every button in that screen issues an ordinary {@code /btb} command,
 * so the server's normal permission check applies to each action — the screen cannot do anything
 * the operator couldn't type.
 *
 * <p>{@code /btb resetall} returns the whole set to its initial state.
 */
public final class DirectorManager implements CinematicModule, CommandContributor {
	private CinematicEngine engine;

	@Override
	public String id() {
		return "director";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
	}

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		root.then(literal("director")
				.executes(c -> send(c.getSource().getPlayerOrException(), "director_open"))
				.then(literal("refresh").executes(c -> send(c.getSource().getPlayerOrException(), "director_state"))));
		root.then(literal("demo")
				.then(literal("setup").executes(c -> {
					var src = c.getSource();
					try {
						String err = DemoSet.build(engine, src.getLevel(), net.minecraft.core.BlockPos.containing(src.getPosition()));
						return err == null ? Feedback.ok(src, "Demo set built. Try: /btb scene start heart_discovery  ·  /btb destruction trigger ruin_tower  ·  full storyboard set: /btb film setup")
								: Feedback.fail(src, err);
					} catch (java.io.IOException e) {
						return Feedback.fail(src, "Backup failed, nothing built: " + e.getMessage());
					}
				}))
				.then(literal("remove").executes(c -> {
					try {
						String err = DemoSet.remove(engine, c.getSource().getLevel());
						return err == null ? Feedback.ok(c.getSource(), "Demo set removed, terrain restored") : Feedback.fail(c.getSource(), err);
					} catch (java.io.IOException e) {
						return Feedback.fail(c.getSource(), "Restore failed: " + e.getMessage());
					}
				})));
		// The complete film set for the storyboard (bastion → bridges → desert → fortress/tower → portal hall → exit).
		root.then(literal("film")
				.then(literal("setup").executes(c -> filmBuild(c.getSource(), null)))
				// trailer to your song (config/btb_cinematic/music/trailer.ogg|mp3): play live or export with sound
				.then(literal("trailer").executes(c -> trailer(c.getSource(), null, 0))
						.then(literal("export").executes(c -> trailer(c.getSource(), "4k", 60))
								.then(argument("res", com.mojang.brigadier.arguments.StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("4k", "1440p", "1080p"), b))
										.executes(c -> trailer(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "res"), 60))
										.then(argument("fps", com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 120))
												.executes(c -> trailer(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "res"),
														com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "fps")))))))
				.then(literal("piece").then(argument("teil", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(FilmSet.PIECES.keySet(), b))
						.executes(c -> filmBuild(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "teil")))))
				.then(literal("remove").executes(c -> filmRemove(c.getSource(), null))
						.then(argument("teil", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(FilmSet.built(c.getSource().getLevel()), b))
								.executes(c -> {
									String t = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "teil");
									return filmRemove(c.getSource(), t.equals("komplett") ? null : t);
								})))
				.then(literal("list").executes(c -> {
					List<String> b = FilmSet.built(c.getSource().getLevel());
					return Feedback.ok(c.getSource(), "Teile: " + String.join(", ", FilmSet.PIECES.keySet())
							+ " · gebaut: " + (b.isEmpty() ? "nichts" : String.join(", ", b)));
				}))
				.then(literal("chunks")
						.then(literal("on").executes(c -> filmChunks(c.getSource(), true)))
						.then(literal("off").executes(c -> filmChunks(c.getSource(), false)))));
		// Seelenkompass: a compass whose needle always points to the heart (or to any spot you give it)
		root.then(literal("kompass")
				.executes(c -> soulCompass(c.getSource(), null))
				.then(argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
						.executes(c -> soulCompass(c.getSource(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos")))));
		root.then(literal("resetall").executes(c -> {
			int n = resetAll();
			return Feedback.ok(c.getSource(), "Set reset (" + n + " scene(s) reset, portals closed, areas restored, effects cleared)");
		}));
	}

	private int send(ServerPlayer player, String cueType) {
		JsonObject st = state();
		// Scene builder: steps of every scene + whether this player is recording one live.
		JsonObject ed = de.behindtheblocks.cinematic.scene.SceneEditor.instance() == null ? new JsonObject()
				: de.behindtheblocks.cinematic.scene.SceneEditor.instance().directorState(player.getName().getString());
		st.add("scene_events", ed.has("events") ? ed.get("events") : new JsonObject());
		if (ed.has("recording")) {
			st.add("recording", ed.get("recording"));
		}
		// Credits entries, so the Credits tab can show and edit them.
		JsonObject credits = new JsonObject();
		engine.module(HudManager.class).ifPresent(h -> h.ids().forEach(id -> {
			JsonObject d = h.definition(id);
			String style = d != null && d.has("style") ? d.get("style").getAsString() : "";
			if (style.equals("credits") || style.equals("roll") || style.equals("end_card")) {
				JsonObject c = new JsonObject();
				c.addProperty("style", style);
				JsonArray list = new JsonArray();
				if (d.has("entries")) {
					d.getAsJsonArray("entries").forEach(e -> list.add(HudManager.describe(e.getAsJsonObject())));
				}
				c.add("entries", list);
				c.add("raw", d.has("entries") ? d.getAsJsonArray("entries").deepCopy() : new JsonArray());
				c.addProperty("tempo", style.equals("roll") ? (d.has("roll_speed") ? d.get("roll_speed").getAsFloat() : 0.6f)
						: (d.has("entry_ticks") ? d.get("entry_ticks").getAsInt() : 70) / 20f);
				credits.add(id, c);
			}
		}));
		st.add("credits", credits);
		engine.cues().queue(TimelineManager.MANUAL, 0, cueType, st, List.of(player));
		return 1;
	}

	/** Snapshot of everything the Director screen can control. */
	public JsonObject state() {
		JsonObject o = new JsonObject();
		JsonArray scenes = new JsonArray();
		for (String id : engine.scenes().sceneIds()) {
			JsonObject s = new JsonObject();
			s.addProperty("id", id);
			ScenePlayback pb = engine.scenes().activePlaybacks().stream().filter(p -> p.scene().id().equals(id)).findFirst().orElse(null);
			s.addProperty("state", pb == null ? "stopped" : pb.state().name().toLowerCase());
			s.addProperty("tick", pb == null ? 0 : pb.tick());
			s.addProperty("duration", engine.scenes().definition(id).map(d -> d.duration()).orElse(0));
			scenes.add(s);
		}
		o.add("scenes", scenes);
		o.add("cameras", array(engine.module(CameraManager.class).map(CameraManager::presetIds).orElse(List.of())));
		JsonArray showcases = new JsonArray();
		engine.module(ShowcaseManager.class).ifPresent(sm -> sm.ids().forEach(id -> {
			JsonObject s = new JsonObject();
			s.addProperty("id", id);
			s.addProperty("running", sm.runningIds().contains(id));
			showcases.add(s);
		}));
		o.add("showcases", showcases);
		JsonArray portals = new JsonArray();
		engine.module(PortalManager.class).ifPresent(pm -> pm.ids().forEach(id -> {
			JsonObject s = new JsonObject();
			s.addProperty("id", id);
			s.addProperty("state", pm.state(id).name());
			portals.add(s);
		}));
		o.add("portals", portals);
		JsonArray courses = new JsonArray();
		engine.module(de.behindtheblocks.cinematic.parkour.ParkourManager.class).ifPresent(pm -> pm.ids().forEach(id -> {
			JsonObject c = new JsonObject();
			c.addProperty("id", id);
			c.addProperty("running", pm.running(id));
			courses.add(c);
		}));
		o.add("parkour", courses);
		o.add("triggers", array(engine.module(de.behindtheblocks.cinematic.story.TriggerManager.class)
				.map(de.behindtheblocks.cinematic.story.TriggerManager::ids).orElse(List.of())));
		JsonArray amb = new JsonArray();
		engine.module(de.behindtheblocks.cinematic.ambience.AmbienceManager.class).ifPresent(am -> am.ids().forEach(id -> {
			JsonObject a = new JsonObject();
			a.addProperty("id", id);
			a.addProperty("enabled", am.isEnabled(id));
			amb.add(a);
		}));
		o.add("ambience", amb);
		o.add("destruction", array(engine.module(DestructionManager.class).map(DestructionManager::ids).orElse(List.of())));
		o.add("hud", array(engine.module(HudManager.class).map(HudManager::ids).orElse(List.of())));
		o.add("sounds", array(BtbSounds.all().stream().map(s -> s.location().getPath()).filter(s -> !s.equals("portal")).toList()));
		o.add("players", array(engine.server().map(s -> s.getPlayerList().getPlayers().stream()
				.map(p -> p.getName().getString()).toList()).orElse(List.of())));
		return o;
	}

	private static JsonArray array(java.util.Collection<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}

	/** Resets every scene and module to the initial state of the set. */
	public int resetAll() {
		List<String> ids = List.copyOf(engine.scenes().sceneIds());
		ids.forEach(engine.scenes()::reset); // modules restore what each scene changed
		engine.module(PortalManager.class).ifPresent(pm -> List.copyOf(pm.ids()).forEach(pm::deactivate));
		engine.module(DestructionManager.class).ifPresent(dm -> List.copyOf(dm.ids()).forEach(dm::restore));
		engine.module(ShowcaseManager.class).ifPresent(ShowcaseManager::stopAll);
		engine.module(EffectManager.class).ifPresent(fx -> fx.send(TimelineManager.MANUAL, 0,
				new EffectSpec(EffectSpec.Kind.CLEAR, 0, 0, 1, 10, 0, 0, "all"), null));
		engine.module(HudManager.class).ifPresent(h -> h.hide(TimelineManager.MANUAL, 0, null, 10, null));
		engine.module(de.behindtheblocks.cinematic.story.SpawnManager.class).ifPresent(sp -> sp.clear(null));
		engine.module(de.behindtheblocks.cinematic.story.TriggerManager.class).ifPresent(de.behindtheblocks.cinematic.story.TriggerManager::rearm);
		engine.module(de.behindtheblocks.cinematic.parkour.ParkourManager.class).ifPresent(pm -> List.copyOf(pm.ids()).forEach(pm::stop));
		engine.server().ifPresent(server -> engine.module(CameraManager.class)
				.ifPresent(cam -> cam.stop(TimelineManager.MANUAL, 0, server.getPlayerList().getPlayers())));
		engine.server().ifPresent(server -> engine.module(CameraManager.class).ifPresent(cam -> {
			cam.slowmo(server, 1f, 0);
			cam.handheld(TimelineManager.MANUAL, 0, 0, server.getPlayerList().getPlayers());
		}));
		engine.module(de.behindtheblocks.cinematic.ambience.AmbienceManager.class).ifPresent(
				de.behindtheblocks.cinematic.ambience.AmbienceManager::resetLooks);
		JsonObject stop = new JsonObject();
		stop.addProperty("fade", 10);
		engine.cues().queue(TimelineManager.MANUAL, 0, "sound_stop", stop);
		JsonObject music = new JsonObject();
		music.addProperty("stop", true);
		music.addProperty("fade", 20);
		engine.cues().queue(TimelineManager.MANUAL, 0, "music", music);
		return ids.size();
	}

	private int trailer(net.minecraft.commands.CommandSourceStack src, String res, int fps) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		if (!engine.scenes().sceneIds().contains("trailer")) {
			return Feedback.fail(src, "Der Trailer braucht das ganze Film-Set: /btb film setup");
		}
		var player = src.getPlayerOrException();
		var server = src.getServer();
		var quiet = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(quiet, "btb resetall");
		player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR); // the viewer is invisible and flies with the shots
		if (res == null) {
			server.getCommands().performPrefixedCommand(quiet, "btb scene restart trailer");
			return Feedback.ok(src, "▶ Trailer (2:05). Musik: config/btb_cinematic/music/trailer.ogg (Lautstärke: Regler „Jukebox“). Sichtweite ≥ 16!");
		}
		int w = res.equals("1080p") ? 1920 : res.equals("1440p") ? 2560 : 3840, h = w * 9 / 16;
		JsonObject o = new JsonObject();
		o.addProperty("name", "trailer");
		o.addProperty("seconds", 125.1);
		o.addProperty("width", w);
		o.addProperty("height", h);
		o.addProperty("fps", fps);
		o.addProperty("scene", "trailer");
		o.addProperty("music", "trailer");
		engine.cues().queue(TimelineManager.MANUAL, 0, "export_start", o, List.of(player));
		return Feedback.ok(src, "Trailer-Export " + w + "×" + h + " @ " + fps + " FPS startet – der Song wird am Ende ins Video gelegt");
	}

	private int filmBuild(net.minecraft.commands.CommandSourceStack src, String piece) {
		try {
			// pieces and the whole set run in the direction the player looks (east/south/west/north)
			net.minecraft.core.Direction facing = net.minecraft.core.Direction.fromYRot(src.getRotation().y);
			String err = FilmSet.build(engine, src.getLevel(), net.minecraft.core.BlockPos.containing(src.getPosition()), facing, piece);
			if (err != null && (err.startsWith("Unbekannt") || err.contains("steht schon"))) {
				return Feedback.fail(src, err);
			}
			Feedback.ok(src, (piece == null ? "Film-Set gebaut" : "„" + piece + "“ gebaut") + " (Richtung " + facing.getName()
					+ "). Entfernen: /btb film remove" + (piece == null ? "" : " " + piece)
					+ (piece == null ? " · Überblick: /btb scene start film_vorschau" : ""));
			return err == null ? 1 : Feedback.fail(src, err);
		} catch (java.io.IOException e) {
			return Feedback.fail(src, "Backup fehlgeschlagen, nichts gebaut: " + e.getMessage());
		}
	}

	private int filmRemove(net.minecraft.commands.CommandSourceStack src, String piece) {
		try {
			String err = FilmSet.remove(engine, src.getLevel(), piece);
			return err == null ? Feedback.ok(src, (piece == null ? "Film-Set" : "„" + piece + "“") + " entfernt, Gelände wiederhergestellt")
					: Feedback.fail(src, err);
		} catch (java.io.IOException e) {
			return Feedback.fail(src, "Wiederherstellen fehlgeschlagen: " + e.getMessage());
		}
	}

	private int filmChunks(net.minecraft.commands.CommandSourceStack src, boolean on) {
		try {
			int n = FilmSet.chunksAll(src.getLevel(), on);
			return Feedback.ok(src, n + " Chunks " + (on ? "bleiben jetzt geladen" : "freigegeben"));
		} catch (java.io.IOException e) {
			return Feedback.fail(src, e.getMessage());
		}
	}

	private int soulCompass(net.minecraft.commands.CommandSourceStack src, net.minecraft.core.BlockPos target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var player = src.getPlayerOrException();
		var dim = src.getLevel().dimension();
		if (target == null) {
			var heart = engine.module(ShowcaseManager.class).flatMap(sm -> sm.definition("herz"));
			if (heart.isPresent() && heart.get().has("pos")) {
				var a = heart.get().getAsJsonArray("pos");
				target = net.minecraft.core.BlockPos.containing(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
			} else {
				target = net.minecraft.core.BlockPos.containing(src.getPosition());
			}
		}
		var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS);
		stack.set(net.minecraft.core.component.DataComponents.LODESTONE_TRACKER, new net.minecraft.world.item.component.LodestoneTracker(
				java.util.Optional.of(net.minecraft.core.GlobalPos.of(dim, target)), false));
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Seelenkompass")
				.withStyle(s -> s.withItalic(false).withColor(0xFF6A3A)));
		stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
				net.minecraft.network.chat.Component.literal("Zeigt immer zum Herzen").withStyle(s -> s.withColor(0x9A7A6A)))));
		stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		if (!player.getInventory().add(stack)) {
			player.spawnAtLocation(src.getLevel(), stack);
		}
		return Feedback.ok(src, "Seelenkompass zeigt auf " + target.getX() + " " + target.getY() + " " + target.getZ());
	}
}
