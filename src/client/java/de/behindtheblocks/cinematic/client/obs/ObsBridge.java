package de.behindtheblocks.cinematic.client.obs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Talks to OBS Studio through its built-in obs-websocket (protocol v5, OBS 28+).
 *
 * <p>Purpose: the director usually records from the same client. While the Director screen is
 * open, the recording is paused so the menu never ends up in the footage; scenes/cues can start,
 * stop, pause and resume the recording or switch the OBS scene.
 *
 * <p>Config: {@code config/btb_cinematic/obs.json} (client side, created on first start; disabled by
 * default). Everything is asynchronous and fail-safe: if OBS is not running nothing happens.
 */
public final class ObsBridge {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/obs");

	private static boolean enabled;
	private static String host = "127.0.0.1";
	private static int port = 4455;
	private static String password = "";
	private static boolean pauseWhileMenu = true;

	private static volatile WebSocket socket;
	private static volatile boolean identified;
	private static volatile boolean pausedByMenu;
	private static volatile long pausedAt;
	/** OBS confirmed the menu is out of the footage (paused, stopped or not recording at all). */
	private static volatile boolean confirmed;
	/** OBS could not pause (no pause in its output settings): the recording was stopped and restarts on close. */
	private static volatile boolean stoppedByMenu;
	/** OBS needs a moment to act on PauseRecord: menus/monitor only appear after this delay. */
	private static final long PAUSE_SETTLE_MS = 200;
	private static volatile String status = "aus";
	private static final StringBuilder buffer = new StringBuilder();

	private ObsBridge() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("obs.json");
	}

	public static void init() {
		load();
		// Scenes can control OBS: {"type": "client_cue", "cue": "btb_cinematic:obs", "data": {"action": "start"}}
		ClientCueDispatcher.register("btb_cinematic:obs", (scene, tick, data) -> {
			String action = data.has("action") ? data.get("action").getAsString() : "";
			command(action);
		});
	}

	public static void load() {
		try {
			Path f = file();
			if (!Files.exists(f)) {
				JsonObject o = new JsonObject();
				o.addProperty("_help", "OBS: Tools > WebSocket Server Settings > enable, copy port/password here, set enabled=true.");
				o.addProperty("enabled", false);
				o.addProperty("host", "127.0.0.1");
				o.addProperty("port", 4455);
				o.addProperty("password", "");
				o.addProperty("pause_while_menu", true);
				Files.createDirectories(f.getParent());
				Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
			}
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
			host = o.has("host") ? o.get("host").getAsString() : host;
			port = o.has("port") ? o.get("port").getAsInt() : port;
			password = o.has("password") ? o.get("password").getAsString() : "";
			pauseWhileMenu = !o.has("pause_while_menu") || o.get("pause_while_menu").getAsBoolean();
			status = enabled ? "nicht verbunden" : "aus";
		} catch (Exception e) {
			LOG.warn("obs.json unreadable: {}", e.getMessage());
		}
	}

	private static volatile String version = "";

	/** Director "OBS testen": connects and asks OBS for its version and recording state. */
	public static void test() {
		if (!enabled) {
			status = "aus – erst Adresse/Passwort eintragen und „Speichern & verbinden“";
			return;
		}
		request("GetVersion", null);
		request("GetRecordStatus", null);
	}

	public static String host() {
		return host;
	}

	public static int port() {
		return port;
	}

	public static boolean hasPassword() {
		return !password.isEmpty();
	}

	/** Settings from the Director (no file editing). An empty password keeps the stored one. */
	public static void configure(boolean on, String newHost, int newPort, String newPassword) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("enabled", on);
			o.addProperty("host", newHost.isBlank() ? "127.0.0.1" : newHost.trim());
			o.addProperty("port", newPort);
			o.addProperty("password", newPassword.isEmpty() ? password : newPassword);
			o.addProperty("pause_while_menu", pauseWhileMenu);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOG.warn("obs.json could not be written: {}", e.getMessage());
		}
		WebSocket ws = socket;
		socket = null;
		identified = false;
		if (ws != null) {
			ws.abort();
		}
		load();
		if (enabled) {
			ensureConnected();
		}
	}

	public static String status() {
		return status;
	}

	public static boolean enabled() {
		return enabled;
	}

	// ------------------------------------------------------------------ menu hooks

	public static void onMenuOpened() {
		if (enabled && pauseWhileMenu && !pausedByMenu) {
			pausedByMenu = true;
			confirmed = false;
			pausedAt = System.currentTimeMillis();
			request("PauseRecord", null);
		}
	}

	/**
	 * May director UI be drawn now without ending up in the recording? True when OBS is not in use,
	 * or once the pause request has had time to take effect.
	 */
	public static boolean uiMayShow() {
		if (!enabled || !pauseWhileMenu || socket == null || !identified) {
			return true;
		}
		long t = System.currentTimeMillis() - pausedAt;
		// wait for OBS' answer (pause or – fallback – stop); never block the menu longer than 2.5 s
		return pausedByMenu && (confirmed && t >= 80 || t >= 2500);
	}

	public static void onMenuClosed() {
		if (enabled && pausedByMenu) {
			pausedByMenu = false;
			if (stoppedByMenu) {
				stoppedByMenu = false;
				request("StartRecord", null); // next take = next file
			} else {
				request("ResumeRecord", null);
			}
		}
	}

	/** {@code start | stop | pause | resume | toggle | scene:<OBS scene name>} */
	public static void command(String action) {
		if (!enabled) {
			return;
		}
		switch (action) {
			case "start" -> request("StartRecord", null);
			case "stop" -> request("StopRecord", null);
			case "pause" -> request("PauseRecord", null);
			case "resume" -> request("ResumeRecord", null);
			case "toggle" -> request("ToggleRecord", null);
			default -> {
				if (action.startsWith("scene:")) {
					JsonObject d = new JsonObject();
					d.addProperty("sceneName", action.substring(6));
					request("SetCurrentProgramScene", d);
				} else {
					LOG.warn("Unknown OBS action '{}'", action);
				}
			}
		}
	}

	// ------------------------------------------------------------------ protocol

	/** Requests waiting for their answer (requestId → future of the responseData, null on failure). */
	private static final java.util.Map<String, java.util.concurrent.CompletableFuture<JsonObject>> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

	/** Sends a request and completes with its responseData (empty object) or null if OBS refused / is not there. */
	private static java.util.concurrent.CompletableFuture<JsonObject> call(String type, JsonObject data) {
		java.util.concurrent.CompletableFuture<JsonObject> f = new java.util.concurrent.CompletableFuture<>();
		ensureConnected().thenAccept(ok -> {
			if (!ok) {
				f.complete(null);
				return;
			}
			String id = UUID.randomUUID().toString();
			PENDING.put(id, f);
			JsonObject d = new JsonObject();
			d.addProperty("requestType", type);
			d.addProperty("requestId", id);
			if (data != null) {
				d.add("requestData", data);
			}
			send(6, d);
		});
		return f.completeOnTimeout(null, 3, java.util.concurrent.TimeUnit.SECONDS);
	}

	/** Name of the OBS source that shows the second client (the actor's view). */
	public static final String PIP_SOURCE = "BTB PiP";

	/**
	 * Picture in picture: shows/hides the OBS source {@value #PIP_SOURCE} in the current scene and puts it
	 * into a corner (tl/tr/bl/br) at the given width (share of the picture). The second Minecraft window,
	 * which spectates the actor, is captured by that source.
	 */
	public static void pip(boolean on, String corner, float size) {
		if (!enabled) {
			return;
		}
		call("GetCurrentProgramScene", null).thenAccept(sc -> {
			if (sc == null) {
				status = "Bild-im-Bild: OBS antwortet nicht";
				return;
			}
			String scene = sc.has("currentProgramSceneName") ? sc.get("currentProgramSceneName").getAsString() : sc.get("sceneName").getAsString();
			JsonObject q = new JsonObject();
			q.addProperty("sceneName", scene);
			q.addProperty("sourceName", PIP_SOURCE);
			call("GetSceneItemId", q).thenAccept(item -> {
				if (item == null || !item.has("sceneItemId")) {
					status = "Bild-im-Bild: In der OBS-Szene „" + scene + "“ fehlt die Quelle „" + PIP_SOURCE + "“";
					return;
				}
				int itemId = item.get("sceneItemId").getAsInt();
				call("GetVideoSettings", null).thenAccept(v -> {
					if (on && v != null) {
						double bw = v.get("baseWidth").getAsDouble(), bh = v.get("baseHeight").getAsDouble();
						double[] r = pipRect(corner, size, bw, bh);
						JsonObject t = new JsonObject();
						t.addProperty("positionX", r[0]);
						t.addProperty("positionY", r[1]);
						t.addProperty("alignment", 5);
						t.addProperty("boundsType", "OBS_BOUNDS_SCALE_INNER");
						t.addProperty("boundsAlignment", 0);
						t.addProperty("boundsWidth", r[2]);
						t.addProperty("boundsHeight", r[3]);
						JsonObject st = new JsonObject();
						st.addProperty("sceneName", scene);
						st.addProperty("sceneItemId", itemId);
						st.add("sceneItemTransform", t);
						call("SetSceneItemTransform", st);
					}
					JsonObject en = new JsonObject();
					en.addProperty("sceneName", scene);
					en.addProperty("sceneItemId", itemId);
					en.addProperty("sceneItemEnabled", on);
					call("SetSceneItemEnabled", en).thenAccept(res -> status = res == null ? "Bild-im-Bild: OBS-Fehler"
							: on ? "Bild-im-Bild an" : "Bild-im-Bild aus");
				});
			});
		});
	}

	/** x, y, width, height of the inset in a picture of w × h (same rule for OBS and for the frame in the game). */
	public static double[] pipRect(String corner, float size, double w, double h) {
		double m = h * 0.04, pw = w * size, ph = pw * h / w;
		double x = corner.endsWith("l") ? m : w - pw - m;
		double y = corner.startsWith("t") ? m : h - ph - m;
		return new double[] {x, y, pw, ph};
	}

	private static void request(String type, JsonObject data) {
		ensureConnected().thenAccept(ok -> {
			if (!ok) {
				return;
			}
			JsonObject d = new JsonObject();
			d.addProperty("requestType", type);
			d.addProperty("requestId", UUID.randomUUID().toString());
			if (data != null) {
				d.add("requestData", data);
			}
			send(6, d);
			LOG.info("OBS ← {}", type);
		});
	}

	private static java.util.concurrent.CompletableFuture<Boolean> ensureConnected() {
		if (socket != null && identified) {
			return java.util.concurrent.CompletableFuture.completedFuture(true);
		}
		java.util.concurrent.CompletableFuture<Boolean> ready = new java.util.concurrent.CompletableFuture<>();
		identified = false;
		status = "verbinde…";
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).proxy(HttpClient.Builder.NO_PROXY).build()
				.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
				.buildAsync(URI.create("ws://" + host + ":" + port), new Listener(ready))
				.whenComplete((ws, err) -> {
					if (err != null) {
						status = "OBS nicht erreichbar";
						LOG.warn("OBS not reachable at {}:{} ({})", host, port, err.getMessage());
						ready.complete(false);
					} else {
						socket = ws;
					}
				});
		return ready.completeOnTimeout(false, 4, java.util.concurrent.TimeUnit.SECONDS);
	}

	private static void send(int op, JsonObject d) {
		WebSocket ws = socket;
		if (ws == null) {
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("op", op);
		msg.add("d", d);
		ws.sendText(msg.toString(), true);
	}

	/** obs-websocket v5 auth: base64(sha256(base64(sha256(password + salt)) + challenge)). */
	static String auth(String password, String salt, String challenge) throws Exception {
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		String secret = Base64.getEncoder().encodeToString(sha.digest((password + salt).getBytes(StandardCharsets.UTF_8)));
		sha.reset();
		return Base64.getEncoder().encodeToString(sha.digest((secret + challenge).getBytes(StandardCharsets.UTF_8)));
	}

	private static final class Listener implements WebSocket.Listener {
		private final java.util.concurrent.CompletableFuture<Boolean> ready;

		Listener(java.util.concurrent.CompletableFuture<Boolean> ready) {
			this.ready = ready;
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			synchronized (buffer) {
				buffer.append(data);
				if (last) {
					handle(ws, buffer.toString());
					buffer.setLength(0);
				}
			}
			ws.request(1);
			return null;
		}

		private void handle(WebSocket ws, String text) {
			try {
				JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
				int op = msg.get("op").getAsInt();
				JsonObject d = msg.getAsJsonObject("d");
				if (op == 0) { // Hello → Identify
					socket = ws;
					JsonObject id = new JsonObject();
					id.addProperty("rpcVersion", 1);
					id.addProperty("eventSubscriptions", 0);
					if (d.has("authentication")) {
						JsonObject a = d.getAsJsonObject("authentication");
						id.addProperty("authentication", auth(password, a.get("salt").getAsString(), a.get("challenge").getAsString()));
					}
					send(1, id);
				} else if (op == 2) { // Identified
					identified = true;
					status = "verbunden";
					LOG.info("Connected to OBS at {}:{}", host, port);
					ready.complete(true);
				} else if (op == 7) { // RequestResponse
					JsonObject st = d.getAsJsonObject("requestStatus");
					String type = d.get("requestType").getAsString();
					var waiting = d.has("requestId") ? PENDING.remove(d.get("requestId").getAsString()) : null;
					if (waiting != null) {
						boolean ok = st == null || st.get("result").getAsBoolean();
						waiting.complete(ok ? (d.has("responseData") ? d.getAsJsonObject("responseData") : new JsonObject()) : null);
						if (!type.startsWith("Get")) {
							LOG.info("OBS: {} → {}", type, ok ? "ok" : st);
						}
						return;
					}
					if (type.equals("PauseRecord") && pausedByMenu) {
						boolean ok = st == null || st.get("result").getAsBoolean();
						int pc = st != null && st.has("code") ? st.get("code").getAsInt() : 0;
						if (ok || pc == 501 || pc == 502) {
							confirmed = true; // paused, not recording, or already paused
						} else {
							// This OBS setup cannot pause: stop the recording instead, start it again on close.
							stoppedByMenu = true;
							status = "OBS kann nicht pausieren → Aufnahme wird gestoppt und beim Schließen neu gestartet";
							request("StopRecord", null);
							return;
						}
					}
					if (type.equals("StopRecord") && stoppedByMenu) {
						confirmed = true;
					}
					if (st != null && !st.get("result").getAsBoolean()) {
						int code = st.has("code") ? st.get("code").getAsInt() : 0;
						LOG.info("OBS: {} → {}", type, st.has("comment") ? st.get("comment").getAsString() : code);
						// Explain the usual cases in the Director instead of failing silently.
						status = switch (code) {
							case 501 -> type.contains("Pause") || type.contains("Resume")
									? "Pause geht nur, während OBS aufnimmt" : "OBS nimmt gerade nicht auf";
							case 500 -> "OBS nimmt schon auf";
							case 502 -> "Aufnahme ist schon pausiert";
							case 702 -> "OBS kann diese Aufnahme nicht pausieren (Einstellungen → Ausgabe)";
							default -> "OBS-Fehler bei " + type + " (" + code + ")";
						};
					} else if (type.equals("GetRecordStatus") && d.has("responseData")) {
						JsonObject rd = d.getAsJsonObject("responseData");
						boolean active = rd.has("outputActive") && rd.get("outputActive").getAsBoolean();
						boolean paused = rd.has("outputPaused") && rd.get("outputPaused").getAsBoolean();
						status = "verbunden" + (version.isEmpty() ? "" : " (OBS " + version + ")") + " · "
								+ (!active ? "nimmt NICHT auf – Pause geht erst während einer Aufnahme" : paused ? "Aufnahme pausiert" : "nimmt auf");
					} else if (type.equals("GetVersion") && d.has("responseData")) {
						version = d.getAsJsonObject("responseData").get("obsVersion").getAsString();
					} else if (type.equals("PauseRecord")) {
						status = "verbunden · Aufnahme pausiert";
					} else if (type.equals("ResumeRecord") || type.equals("StartRecord")) {
						status = "verbunden · nimmt auf";
					} else if (type.equals("StopRecord")) {
						status = "verbunden · gestoppt";
					}
				}
			} catch (Exception e) {
				LOG.warn("Bad OBS message: {}", e.getMessage());
			}
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			socket = null;
			identified = false;
			status = code == 4009 ? "falsches OBS-Passwort" : "getrennt";
			ready.complete(false);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			socket = null;
			identified = false;
			status = "Fehler";
			ready.complete(false);
		}
	}
}
