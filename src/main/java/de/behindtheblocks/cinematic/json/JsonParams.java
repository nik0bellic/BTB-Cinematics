package de.behindtheblocks.cinematic.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed, validating accessor around a {@link JsonObject}.
 *
 * <p>All getters throw {@link JsonParamException} with a human readable message that names the
 * offending key. Scene loading turns those into load problems, so authors get precise feedback
 * like {@code event #3 (teleport): 'pos' must be an array of 3 numbers} instead of a stack trace.
 */
public final class JsonParams {
	private final JsonObject json;

	public JsonParams(JsonObject json) {
		this.json = json;
	}

	public JsonObject raw() {
		return json;
	}

	public boolean has(String key) {
		return json.has(key) && !json.get(key).isJsonNull();
	}

	public String requireString(String key) {
		JsonElement e = require(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw new JsonParamException("'" + key + "' must be a string");
		}
		return e.getAsString();
	}

	public String getString(String key, String fallback) {
		return has(key) ? requireString(key) : fallback;
	}

	public double requireDouble(String key) {
		JsonElement e = require(key);
		if (!isNumber(e)) {
			throw new JsonParamException("'" + key + "' must be a number");
		}
		double d = e.getAsDouble();
		if (!Double.isFinite(d)) {
			throw new JsonParamException("'" + key + "' must be finite");
		}
		return d;
	}

	public double getDouble(String key, double fallback) {
		return has(key) ? requireDouble(key) : fallback;
	}

	public float getFloat(String key, float fallback) {
		return (float) getDouble(key, fallback);
	}

	public int requireInt(String key) {
		double d = requireDouble(key);
		if (d != Math.rint(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
			throw new JsonParamException("'" + key + "' must be a whole number");
		}
		return (int) d;
	}

	public int getInt(String key, int fallback) {
		return has(key) ? requireInt(key) : fallback;
	}

	public boolean getBoolean(String key, boolean fallback) {
		if (!has(key)) {
			return fallback;
		}
		JsonElement e = json.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new JsonParamException("'" + key + "' must be true or false");
		}
		return e.getAsBoolean();
	}

	/** Reads a fixed-size numeric array, e.g. a position {@code [x, y, z]}. */
	public double[] requireDoubleArray(String key, int size) {
		JsonElement e = require(key);
		if (!e.isJsonArray() || e.getAsJsonArray().size() != size) {
			throw new JsonParamException("'" + key + "' must be an array of " + size + " numbers");
		}
		JsonArray arr = e.getAsJsonArray();
		double[] out = new double[size];
		for (int i = 0; i < size; i++) {
			if (!isNumber(arr.get(i))) {
				throw new JsonParamException("'" + key + "' must be an array of " + size + " numbers");
			}
			out[i] = arr.get(i).getAsDouble();
			if (!Double.isFinite(out[i])) {
				throw new JsonParamException("'" + key + "' contains a non-finite number");
			}
		}
		return out;
	}

	/** Accepts either a single string or an array of strings. */
	public List<String> getStringList(String key) {
		if (!has(key)) {
			return List.of();
		}
		JsonElement e = json.get(key);
		List<String> out = new ArrayList<>();
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
			out.add(e.getAsString());
		} else if (e.isJsonArray()) {
			for (JsonElement item : e.getAsJsonArray()) {
				if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
					throw new JsonParamException("'" + key + "' must be a string or an array of strings");
				}
				out.add(item.getAsString());
			}
		} else {
			throw new JsonParamException("'" + key + "' must be a string or an array of strings");
		}
		return List.copyOf(out);
	}

	public Optional<JsonObject> getObject(String key) {
		if (!has(key)) {
			return Optional.empty();
		}
		JsonElement e = json.get(key);
		if (!e.isJsonObject()) {
			throw new JsonParamException("'" + key + "' must be an object");
		}
		return Optional.of(e.getAsJsonObject());
	}

	public <E extends Enum<E>> E getEnum(String key, Class<E> type, E fallback) {
		if (!has(key)) {
			return fallback;
		}
		String value = requireString(key);
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equalsIgnoreCase(value)) {
				return constant;
			}
		}
		throw new JsonParamException("'" + key + "' has unknown value '" + value + "'");
	}

	private JsonElement require(String key) {
		if (!has(key)) {
			throw new JsonParamException("missing required field '" + key + "'");
		}
		return json.get(key);
	}

	private static boolean isNumber(JsonElement e) {
		return e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber();
	}
}
