package de.behindtheblocks.cinematic.event.builtin;

import de.behindtheblocks.cinematic.event.CinematicAction;
import de.behindtheblocks.cinematic.event.CinematicEventType;
import de.behindtheblocks.cinematic.event.EventManager;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.scene.SceneLoader;
import de.behindtheblocks.cinematic.scene.SceneManager;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Function;

/** Core timeline events that don't belong to a specific module. */
public final class BuiltinEvents {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/scene-log");

	private BuiltinEvents() {
	}

	public static void register(EventManager events) {
		events.register(simple("command",
				"Runs a server command with game-master permission. {command, silent?, as? player, pos? [x,y,z], rot? [yaw,pitch]}",
				BuiltinEvents::command));
		events.register(simple("message",
				"Sends a chat or action-bar message. {text, actionbar?, players?}",
				BuiltinEvents::message));
		events.register(simple("teleport",
				"Teleports players. {pos:[x,y,z], yaw?, pitch?, dimension?, players?}",
				BuiltinEvents::teleport));
		events.register(simple("scene_start",
				"Starts (or restarts) another scene. {scene}",
				BuiltinEvents::sceneStart));
		events.register(simple("scene_stop",
				"Stops a scene; without 'scene' stops the current one. {scene?}",
				BuiltinEvents::sceneStop));
		events.register(simple("log",
				"Writes a line to the server log (timing checks). {message}",
				BuiltinEvents::log));
	}

	/** Convenience factory for event types defined by a single prepare function. */
	public static CinematicEventType simple(String id, String description, Function<JsonParams, CinematicAction> prepare) {
		return new CinematicEventType() {
			@Override
			public String id() {
				return id;
			}

			@Override
			public String description() {
				return description;
			}

			@Override
			public CinematicAction prepare(JsonParams params) {
				return prepare.apply(params);
			}
		};
	}

	private static CinematicAction command(JsonParams p) {
		String raw = p.requireString("command").trim();
		String command = raw.startsWith("/") ? raw.substring(1) : raw;
		if (command.isEmpty()) {
			throw new JsonParamException("'command' must not be empty");
		}
		boolean silent = p.getBoolean("silent", true);
		// Steps built in the Director / recorded live remember who ran them and from where,
		// so "for me", "~" and "^" behave exactly like during recording.
		String as = p.getString("as", "");
		double[] pos = p.has("pos") ? p.requireDoubleArray("pos", 3) : null;
		double[] rot = p.has("rot") ? p.requireDoubleArray("rot", 2) : null;
		return ctx -> {
			// Game-master level (2), never owner level: scene files can't /stop the server or /op people.
			CommandSourceStack source = ctx.server().createCommandSourceStack()
					.withPermission(LevelBasedPermissionSet.GAMEMASTER);
			if (!as.isEmpty()) {
				ServerPlayer player = ctx.server().getPlayerList().getPlayerByName(as);
				if (player == null && !ctx.server().getPlayerList().getPlayers().isEmpty()) {
					// The person who built the scene is not online (other PC, other account): take the first player.
					player = ctx.server().getPlayerList().getPlayers().get(0);
				}
				if (player != null) {
					source = source.withEntity(player).withLevel(player.level()).withPosition(player.position())
							.withRotation(new net.minecraft.world.phys.Vec2(player.getXRot(), player.getYRot()));
				}
			}
			if (pos != null) {
				source = source.withPosition(new net.minecraft.world.phys.Vec3(pos[0], pos[1], pos[2]));
			}
			if (rot != null) {
				source = source.withRotation(new net.minecraft.world.phys.Vec2((float) rot[1], (float) rot[0]));
			}
			if (silent) {
				source = source.withSuppressedOutput();
			}
			// Vanilla only reports command errors to the (silenced) source. Parse first so syntax
			// errors / unknown commands become event failures the scene's error policy can see.
			String sceneId = ctx.sceneId();
			int tick = ctx.tick();
			source = source.withCallback((success, result) -> {
				if (!success) {
					LOG.warn("[{} @ {}] command '/{}' executed but reported failure", sceneId, tick, command);
				}
			});
			Commands commands = ctx.server().getCommands();
			ParseResults<CommandSourceStack> parsed = commands.getDispatcher().parse(command, source);
			Commands.validateParseResults(parsed);
			if (parsed.getContext().getLastChild().getCommand() == null && parsed.getExceptions().isEmpty()) {
				throw new IllegalArgumentException("incomplete command '/" + command + "'");
			}
			// Run at the end of this tick, outside any command that started the scene: Minecraft queues
			// nested commands, which would lose the scene ownership (pause/stop/reset of its effects).
			ctx.engine().timeline().schedule(sceneId, "command", 0, () ->
					de.behindtheblocks.cinematic.timeline.TimelineManager.runForScene(sceneId, () -> commands.performCommand(parsed, command)));
		};
	}

	private static CinematicAction message(JsonParams p) {
		String text = p.requireString("text");
		boolean actionbar = p.getBoolean("actionbar", false);
		PlayerTargets targets = PlayerTargets.parse(p);
		return ctx -> {
			Component c = Component.literal(text);
			for (ServerPlayer player : targets.resolve(ctx.server())) {
				player.sendSystemMessage(c, actionbar);
			}
		};
	}

	private static CinematicAction teleport(JsonParams p) {
		double[] pos = p.requireDoubleArray("pos", 3);
		boolean keepYaw = !p.has("yaw");
		boolean keepPitch = !p.has("pitch");
		float yaw = p.getFloat("yaw", 0f);
		float pitch = p.getFloat("pitch", 0f);
		if (pitch < -90f || pitch > 90f) {
			throw new JsonParamException("'pitch' must be between -90 and 90");
		}
		String dimension = p.getString("dimension", null);
		Identifier dimId = null;
		if (dimension != null) {
			dimId = Identifier.tryParse(dimension);
			if (dimId == null) {
				throw new JsonParamException("'dimension' is not a valid id: " + dimension);
			}
		}
		PlayerTargets targets = PlayerTargets.parse(p);
		ResourceKey<Level> dimKey = dimId == null ? null : ResourceKey.create(Registries.DIMENSION, dimId);
		return ctx -> {
			for (ServerPlayer player : targets.resolve(ctx.server())) {
				ServerLevel level = dimKey == null ? player.level() : ctx.server().getLevel(dimKey);
				if (level == null) {
					throw new IllegalStateException("Dimension " + dimKey.identifier() + " does not exist");
				}
				float y = keepYaw ? player.getYRot() : yaw;
				float x = keepPitch ? player.getXRot() : pitch;
				player.teleportTo(level, pos[0], pos[1], pos[2], Set.of(), y, x, true);
			}
		};
	}

	private static CinematicAction sceneStart(JsonParams p) {
		String scene = requireSceneId(p, "scene");
		return ctx -> {
			SceneManager.Outcome o = ctx.engine().scenes().restart(scene);
			if (!o.success()) {
				throw new IllegalStateException(o.message());
			}
		};
	}

	private static CinematicAction sceneStop(JsonParams p) {
		String scene = p.has("scene") ? requireSceneId(p, "scene") : null;
		return ctx -> ctx.engine().scenes().stop(scene == null ? ctx.sceneId() : scene);
	}

	private static CinematicAction log(JsonParams p) {
		String message = p.requireString("message");
		return ctx -> LOG.info("[{} @ {}] {}", ctx.sceneId(), ctx.tick(), message);
	}

	private static String requireSceneId(JsonParams p, String key) {
		String id = p.requireString(key);
		if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
			throw new JsonParamException("'" + key + "' is not a valid scene id");
		}
		return id;
	}
}
