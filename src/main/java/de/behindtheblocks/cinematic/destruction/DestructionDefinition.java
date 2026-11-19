package de.behindtheblocks.cinematic.destruction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * A predefined destruction area ({@code config/btb_cinematic/destruction/<id>.json}).
 *
 * <pre>{@code
 * { "dimension": "minecraft:overworld", "from": [x,y,z], "to": [x,y,z],
 *   "effect": "collapse", "duration": 80, "seed": 1234,
 *   "center": [x,y,z],          // explode / lava origin (default: area centre, bottom for lava)
 *   "debris": 250,              // max animated falling blocks
 *   "place_lava": true,         // lava effects place real lava (restored on reset)
 *   "direction": "south",       // wall_burst: side the lava breaks out of (default: towards the nearest player)
 *   "proximity": 5,             // auto-trigger when a player comes this close to the area (0 = off)
 *   "proximity_once": true,     // fire once per take (until restore)
 *   "screen_fx": true,
 *   "warn": 30 }                // ticks of warning first: dust trickles, it creaks – then it comes down         // camera shake / screen flash for players nearby (false = only the world effect)    // fire once per take (until restore)
 * }</pre>
 */
public record DestructionDefinition(String id, Identifier dimension, BlockPos min, BlockPos max, Effect effect, int duration,
		long seed, double[] center, int debris, boolean placeLava, Direction direction, double proximity, boolean proximityOnce, boolean screenFx, int warn) {

	public enum Effect {
		/** Layers fall from top to bottom as tumbling debris; dust clouds where they land. */
		COLLAPSE,
		/** Spherical blast wave from the centre, debris flung outwards. */
		EXPLODE,
		/** Random erosion: blocks crumble away one by one. */
		CRUMBLE,
		/** Lava erupts from the centre: fountain, magma debris, real lava pools. */
		LAVA,
		/** Fire spreads across all exposed top surfaces. */
		FIRE,
		/** Lava breaks out of a wall: the wall cracks open from the centre, glowing debris and a lava jet shoot out. */
		WALL_BURST,
		/** A gate / portcullis that rises smoothly and frees the passage (closes again on restore). */
		GATE;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Effect byId(String id) {
			return valueOf(id.toUpperCase(Locale.ROOT));
		}
	}

	public static final int MAX_VOLUME = 200_000;
	/** Lava and fire spread; the backup covers this margin around the area. */
	public static final int MARGIN = 8;

	public static List<String> effectIds() {
		return java.util.stream.Stream.of(Effect.values()).map(Effect::id).toList();
	}

	public static DestructionDefinition parse(String id, JsonObject json) {
		JsonParams p = new JsonParams(json);
		double[] a = p.requireDoubleArray("from", 3), b = p.requireDoubleArray("to", 3);
		BlockPos min = BlockPos.containing(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]));
		BlockPos max = BlockPos.containing(Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
		long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
		if (volume > MAX_VOLUME) {
			throw new JsonParamException("area too large (" + volume + " blocks, max " + MAX_VOLUME + ")");
		}
		Effect effect;
		try {
			effect = Effect.byId(p.getString("effect", "collapse"));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException("'effect' must be one of " + effectIds());
		}
		int duration = p.getInt("duration", 80);
		if (duration < 1 || duration > 6000) {
			throw new JsonParamException("'duration' must be 1..6000 ticks");
		}
		Identifier dim = Identifier.tryParse(p.getString("dimension", "minecraft:overworld"));
		Direction direction = null;
		if (p.has("direction")) {
			direction = Direction.byName(p.requireString("direction"));
			if (direction == null || direction.getAxis().isVertical()) {
				throw new JsonParamException("'direction' must be north, south, east or west");
			}
		}
		double proximity = p.getDouble("proximity", 0);
		if (proximity < 0 || proximity > 64) {
			throw new JsonParamException("'proximity' must be 0..64 blocks");
		}
		return new DestructionDefinition(id, dim, min, max, effect, duration, (long) p.getDouble("seed", id.hashCode()),
				p.has("center") ? p.requireDoubleArray("center", 3) : null, Math.max(0, Math.min(800, p.getInt("debris", 250))),
				p.getBoolean("place_lava", true), direction, proximity, p.getBoolean("proximity_once", true), p.getBoolean("screen_fx", true),
				Math.max(0, Math.min(400, p.getInt("warn", 0))));
	}

	public BlockPos backupMin() {
		return min.offset(-MARGIN, -2, -MARGIN);
	}

	public BlockPos backupMax() {
		return max.offset(MARGIN, MARGIN, MARGIN);
	}

	public static JsonObject template(String dimension, BlockPos a, BlockPos b) {
		JsonObject o = new JsonObject();
		o.addProperty("dimension", dimension);
		o.add("from", arr(a));
		o.add("to", arr(b));
		o.addProperty("effect", "collapse");
		o.addProperty("duration", 80);
		return o;
	}

	static JsonArray arr(BlockPos p) {
		JsonArray x = new JsonArray();
		x.add(p.getX());
		x.add(p.getY());
		x.add(p.getZ());
		return x;
	}
}
