package de.behindtheblocks.cinematic.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BTB Skin Studio (optional) on the server: stores an actor's look (skin, cosmetics with colours and
 * adjustments, body proportions – plus the files of custom cosmetics/skins) in the replay at the
 * moment of recording, and gives that exact look to the actor's puppet when the take plays – also
 * when the actor is offline or dressed differently by now. Uses Skin Studio's own sync packets.
 */
public final class SkinStudioBridge {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/skinstudio");
	private static final String P = "de.behindtheblocks.skinstudio.";
	private static Object sync;

	private SkinStudioBridge() {
	}

	/** Called by the mixin when Skin Studio starts its server sync. */
	public static void attach(Object serverSync) {
		sync = serverSync;
	}

	public static boolean present() {
		return sync != null;
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(String name) throws ReflectiveOperationException {
		Field f = sync.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(sync);
	}

	// ------------------------------------------------------------------ record <-> json

	private static JsonElement toJson(Object v) throws ReflectiveOperationException {
		if (v == null) {
			return null;
		}
		if (v instanceof String s) {
			return new JsonPrimitive(s);
		}
		if (v instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (v instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (v instanceof UUID u) {
			return new JsonPrimitive(u.toString());
		}
		if (v instanceof Collection<?> c) {
			JsonArray a = new JsonArray();
			for (Object o : c) {
				a.add(toJson(o));
			}
			return a;
		}
		if (v.getClass().isRecord()) {
			JsonObject o = new JsonObject();
			for (RecordComponent rc : v.getClass().getRecordComponents()) {
				JsonElement e = toJson(rc.getAccessor().invoke(v));
				if (e != null) {
					o.add(rc.getName(), e);
				}
			}
			return o;
		}
		throw new IllegalArgumentException("cannot store " + v.getClass());
	}

	private static Object fromJson(Type type, JsonElement e) throws ReflectiveOperationException {
		Class<?> c = type instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : (Class<?>) type;
		if (e == null || e.isJsonNull()) {
			if (c == float.class) {
				return 0f;
			}
			if (c == int.class) {
				return 0;
			}
			if (c == boolean.class) {
				return false;
			}
			return c == String.class ? "" : null;
		}
		if (c == String.class) {
			return e.getAsString();
		}
		if (c == int.class || c == Integer.class) {
			return e.getAsInt();
		}
		if (c == float.class || c == Float.class) {
			return e.getAsFloat();
		}
		if (c == double.class || c == Double.class) {
			return e.getAsDouble();
		}
		if (c == long.class || c == Long.class) {
			return e.getAsLong();
		}
		if (c == boolean.class || c == Boolean.class) {
			return e.getAsBoolean();
		}
		if (c == UUID.class) {
			return UUID.fromString(e.getAsString());
		}
		if (List.class.isAssignableFrom(c)) {
			Type el = ((ParameterizedType) type).getActualTypeArguments()[0];
			List<Object> out = new ArrayList<>();
			for (JsonElement x : e.getAsJsonArray()) {
				out.add(fromJson(el, x));
			}
			return out;
		}
		if (c.isRecord()) {
			RecordComponent[] rcs = c.getRecordComponents();
			Class<?>[] types = new Class<?>[rcs.length];
			Object[] args = new Object[rcs.length];
			JsonObject o = e.getAsJsonObject();
			for (int i = 0; i < rcs.length; i++) {
				types[i] = rcs[i].getType();
				args[i] = fromJson(rcs[i].getGenericType(), o.get(rcs[i].getName()));
			}
			Constructor<?> k = c.getDeclaredConstructor(types);
			k.setAccessible(true);
			return k.newInstance(args);
		}
		throw new IllegalArgumentException("cannot read " + c);
	}

	// ------------------------------------------------------------------ recording

	/** The actor's look right now (null without Skin Studio or when the actor wears nothing special). */
	public static JsonObject snapshot(ServerPlayer p) {
		if (sync == null) {
			return null;
		}
		try {
			Map<UUID, Object> loadouts = field("loadouts");
			Map<UUID, Object> skins = field("skins");
			Map<UUID, Object> bodies = field("bodies");
			Object lo = loadouts.get(p.getUUID()), sk = skins.get(p.getUUID()), bo = bodies.get(p.getUUID());
			if (lo == null && sk == null && bo == null) {
				return null;
			}
			JsonObject o = new JsonObject();
			if (lo != null) {
				o.add("loadout", toJson(lo));
			}
			if (sk != null) {
				o.add("skin", toJson(sk));
			}
			if (bo != null) {
				o.add("body", toJson(bo));
			}
			askForFiles(p, o);
			return o;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("Skin Studio look not stored: {}", e.toString());
			return null;
		}
	}

	private static List<String> hashes(JsonObject look) {
		List<String> out = new ArrayList<>();
		if (look.has("loadout")) {
			for (JsonElement e : look.getAsJsonArray("loadout")) {
				JsonObject en = e.getAsJsonObject();
				if (en.has("hash") && en.get("hash").getAsString().matches("[0-9a-f]{32}")) {
					out.add(en.get("hash").getAsString());
				}
			}
		}
		if (look.has("skin") && look.getAsJsonObject("skin").has("hash")) {
			String h = look.getAsJsonObject("skin").get("hash").getAsString();
			if (h.matches("[0-9a-f]{32}")) {
				out.add(h);
			}
		}
		return out;
	}

	/** Custom cosmetics/skins the server does not have yet: ask the actor's client to send them now. */
	private static void askForFiles(ServerPlayer owner, JsonObject look) throws ReflectiveOperationException {
		Map<String, byte[]> blobs = field("blobs");
		for (String h : hashes(look)) {
			if (!blobs.containsKey(h)) {
				CustomPacketPayload req = (CustomPacketPayload) newRecord(P + "network.SkinStudioPayloads$RequestCosmetic", h);
				if (ServerPlayNetworking.canSend(owner, req.type())) {
					ServerPlayNetworking.send(owner, req);
				}
			}
		}
	}

	/** At the end of the take: put the files of custom cosmetics/skins into the look. */
	public static void storeFiles(JsonObject look) {
		if (sync == null || look == null) {
			return;
		}
		try {
			Map<String, byte[]> blobs = field("blobs");
			JsonObject files = new JsonObject();
			for (String h : hashes(look)) {
				byte[] b = blobs.get(h);
				if (b != null) {
					files.addProperty(h, Base64.getEncoder().encodeToString(b));
				}
			}
			if (!files.isEmpty()) {
				look.add("files", files);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("Skin Studio files not stored: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------ playback

	private static Object newRecord(String cls, Object... args) throws ReflectiveOperationException {
		Class<?> c = Class.forName(cls);
		for (Constructor<?> k : c.getDeclaredConstructors()) {
			if (k.getParameterCount() == args.length) {
				k.setAccessible(true);
				return k.newInstance(args);
			}
		}
		throw new NoSuchMethodException(cls);
	}

	private static void sendToModded(MinecraftServer server, CustomPacketPayload payload) throws ReflectiveOperationException {
		Set<UUID> modded = field("modded");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (modded.contains(p.getUUID()) && ServerPlayNetworking.canSend(p, payload.type())) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** The puppet wears the stored look (as if it were a player with Skin Studio). */
	public static boolean apply(UUID puppet, JsonObject look, MinecraftServer server) {
		if (sync == null || look == null) {
			return false;
		}
		try {
			if (look.has("files")) {
				Map<String, byte[]> blobs = field("blobs");
				JsonObject files = look.getAsJsonObject("files");
				for (String h : files.keySet()) {
					blobs.putIfAbsent(h, Base64.getDecoder().decode(files.get(h).getAsString()));
				}
			}
			Class<?> pl = Class.forName(P + "network.SkinStudioPayloads");
			if (look.has("loadout")) {
				Type entries = Class.forName(P + "network.SkinStudioPayloads$PlayerLoadout").getRecordComponents()[1].getGenericType();
				Object list = fromJson(entries, look.get("loadout"));
				Object one = newRecord(P + "network.SkinStudioPayloads$PlayerLoadout", puppet, list);
				sendToModded(server, (CustomPacketPayload) newRecord(P + "network.SkinStudioPayloads$SyncLoadouts", List.of(one)));
			}
			if (look.has("skin")) {
				Object skin = fromJson(Class.forName(P + "network.SkinStudioPayloads$SkinInfo"), look.get("skin"));
				Object one = newRecord(P + "network.SkinStudioPayloads$PlayerSkinInfo", puppet, skin);
				sendToModded(server, (CustomPacketPayload) newRecord(P + "network.SkinStudioPayloads$SyncSkins", List.of(one)));
			}
			if (look.has("body")) {
				Object body = fromJson(Class.forName(P + "network.SkinStudioPayloads$Body"), look.get("body"));
				Object one = newRecord(P + "network.SkinStudioPayloads$PlayerBody", puppet, body);
				sendToModded(server, (CustomPacketPayload) newRecord(P + "network.SkinStudioPayloads$SyncBodies", List.of(one)));
			}
			return pl != null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("Skin Studio look not given to the puppet: {}", e.toString());
			return false;
		}
	}

	/** The puppet is gone: clients forget its look. */
	public static void remove(UUID puppet, MinecraftServer server) {
		if (sync == null) {
			return;
		}
		try {
			sendToModded(server, (CustomPacketPayload) newRecord(P + "network.SkinStudioPayloads$RemovePlayer", puppet));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.debug("Puppet look not removed: {}", e.toString());
		}
	}
}
