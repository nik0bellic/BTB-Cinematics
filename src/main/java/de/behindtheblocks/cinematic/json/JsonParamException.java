package de.behindtheblocks.cinematic.json;

/** Thrown when a JSON parameter is missing or has the wrong type/value. Message is user facing. */
public class JsonParamException extends RuntimeException {
	public JsonParamException(String message) {
		super(message);
	}
}
