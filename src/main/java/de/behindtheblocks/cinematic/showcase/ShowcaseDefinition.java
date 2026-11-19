package de.behindtheblocks.cinematic.showcase;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Validated showcase configuration. Stored as JSON in {@code config/btb_cinematic/showcases/<id>.json}:
 *
 * <pre>{@code
 * {
 *   "item": "btb_cinematic:heart_of_the_nether", "pos": [0.5, -58, 0.5], "dimension": "minecraft:overworld",
 *   "scale": 1.6, "animation": "showcase", "duration": -1,
 *   "glow": "#FF5A00", "light": true,
 *   "particles": "minecraft:flame", "background": "ring",
 *   "satellites": [ {"item": "btb_cinematic:nether_crystal", "count": 4, "radius": 1.6, "height": 0.2, "speed": 1.5, "scale": 0.5} ],
 *   "camera": {"radius": 4, "height": 1, "degrees": 300, "seconds": 12, "players": "all"},
 *   "sound": "btb_cinematic:heart_reveal", "name_card": "heart_title"
 * }
 * }</pre>
 */
public record ShowcaseDefinition(String id, Item item, double x, double y, double z, Identifier dimension, float scale,
		ShowcasePreset animation, int duration, int glowColor, boolean light, ParticleOptions particle, String background,
		List<Satellite> satellites, JsonObject camera, Identifier sound, String nameCard) {

	public record Satellite(Item item, int count, float radius, float height, float speed, float scale) {
	}

	public static final List<String> BACKGROUNDS = List.of("none", "ring", "embers", "pillar", "aura");

	public static ShowcaseDefinition parse(String id, JsonObject json) {
		JsonParams p = new JsonParams(json);
		Item item = item(p.getString("item", "btb_cinematic:heart_of_the_nether"));
		double[] pos = p.requireDoubleArray("pos", 3);
		Identifier dim = Identifier.tryParse(p.getString("dimension", "minecraft:overworld"));
		if (dim == null) {
			throw new JsonParamException("invalid 'dimension'");
		}
		float scale = p.getFloat("scale", 1.5f);
		if (scale <= 0 || scale > 32) {
			throw new JsonParamException("'scale' must be 0..32");
		}
		ShowcasePreset preset;
		try {
			preset = ShowcasePreset.byId(p.getString("animation", "showcase"));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException("unknown animation preset");
		}
		int glow = p.has("glow") && !p.getString("glow", "none").equals("none") ? Colors.parse(p.getString("glow", "white")) : 0;
		String bg = p.getString("background", "ring");
		if (!BACKGROUNDS.contains(bg)) {
			throw new JsonParamException("'background' must be one of " + BACKGROUNDS);
		}
		List<Satellite> sats = new ArrayList<>();
		if (json.has("satellites")) {
			if (!json.get("satellites").isJsonArray()) {
				throw new JsonParamException("'satellites' must be an array");
			}
			for (JsonElement e : json.getAsJsonArray("satellites")) {
				JsonParams s = new JsonParams(e.getAsJsonObject());
				int count = s.getInt("count", 3);
				if (count < 1 || count > 16) {
					throw new JsonParamException("satellite count must be 1..16");
				}
				sats.add(new Satellite(item(s.getString("item", "btb_cinematic:nether_crystal")), count,
						s.getFloat("radius", 1.5f), s.getFloat("height", 0f), s.getFloat("speed", 1.5f), s.getFloat("scale", 0.5f)));
			}
		}
		Identifier sound = null;
		if (p.has("sound") && !p.getString("sound", "").isEmpty()) {
			sound = Identifier.tryParse(p.getString("sound", ""));
			if (sound == null || !BuiltInRegistries.SOUND_EVENT.containsKey(sound)) {
				throw new JsonParamException("unknown 'sound'");
			}
		}
		return new ShowcaseDefinition(id, item, pos[0], pos[1], pos[2], dim, scale, preset, p.getInt("duration", -1), glow,
				p.getBoolean("light", true), particle(p.getString("particles", "minecraft:flame"), glow), bg, List.copyOf(sats),
				p.getObject("camera").orElse(null), sound, p.getString("name_card", ""));
	}

	private static Item item(String raw) {
		Identifier id = Identifier.tryParse(raw);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
			throw new JsonParamException("unknown item '" + raw + "'");
		}
		return BuiltInRegistries.ITEM.getValue(id);
	}

	/** {@code none}, {@code dust} (uses glow color), or any simple particle id like {@code minecraft:flame}. */
	static ParticleOptions particle(String raw, int glowColor) {
		if (raw.equals("none")) {
			return null;
		}
		if (raw.equals("dust")) {
			return new DustParticleOptions(glowColor == 0 ? 0xFF5A00 : glowColor & 0xFFFFFF, 1.2f);
		}
		Identifier id = Identifier.tryParse(raw);
		ParticleType<?> type = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getValue(id);
		if (type instanceof ParticleOptions options) {
			return options;
		}
		throw new JsonParamException("'particles' must be none, dust or a simple particle id (e.g. minecraft:flame)");
	}

	public static JsonObject template(String itemId, double x, double y, double z, String dimension) {
		JsonObject o = new JsonObject();
		o.addProperty("item", itemId);
		JsonArray pos = new JsonArray();
		pos.add(x);
		pos.add(y);
		pos.add(z);
		o.add("pos", pos);
		o.addProperty("dimension", dimension);
		o.addProperty("scale", 1.5);
		o.addProperty("animation", "showcase");
		o.addProperty("glow", "#FF5A00");
		o.addProperty("light", true);
		o.addProperty("particles", "minecraft:flame");
		o.addProperty("background", "ring");
		return o;
	}
}
