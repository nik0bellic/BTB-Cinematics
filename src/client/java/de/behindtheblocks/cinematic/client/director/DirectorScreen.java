package de.behindtheblocks.cinematic.client.director;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Director Mode: one compact control desk for the whole production.
 *
 * <p>Left: sections. Right: the items of the section with their actions. Bottom: refresh, reset
 * the set, close. Every button sends a normal {@code /btb} command, so the server validates
 * permissions exactly as for typed commands. The screen does not pause the game, so a take can be
 * directed live.
 */
public final class DirectorScreen extends Screen {
	private static final Style TITLE_FONT = Style.EMPTY.withFont(
			new FontDescription.Resource(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "cinematic_bold")));
	private static final int PANEL = 0xF2101014;
	private static final int ACCENT = 0xFFFF7A1A;
	private static final int ROW_H = 22;

	private enum Tab {
		SCENES("Szenen"), BUILDER("Baukasten"), CAMERA("Kamera"), EFFECTS("Effekte"), HUD("HUD"), CREDITS("Credits"), SOUND("Sound"), SHOWCASE("Showcase"),
		PORTALS("Portale"), DESTRUCTION("Zerstörung"), ACTION("Action"), PLAYERS("Spieler"), RECORDING("Aufnahme");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static Tab currentTab = Tab.SCENES;

	/** Where a click on an action goes: run it, or add it to the scene / cue list being built. */
	private enum ClickMode {
		ON_CLOSE("Klick: beim Schließen"), RUN("Klick: sofort"), SCENE("Klick → Szene"), CUES("Klick → Cue-Liste"), SLOT("Klick → Taste");

		final String label;

		ClickMode(String label) {
			this.label = label;
		}
	}

	// Form values survive refreshes of the screen (state updates rebuild all widgets).
	private static ClickMode clickMode = ClickMode.ON_CLOSE;
	/** Actions clicked in "beim Schließen" mode: they run the moment the menu closes (never while it is open/recorded). */
	private static final List<String> PENDING = new ArrayList<>();
	private static boolean showForm = true;
	/** Numpad key (1–9) the next click is put on in "Klick → Taste" mode. */
	private static int targetSlot = 1;
	private static String builderScene = "meine_szene";
	private static String builderTime = "0";
	private static String builderCommand = "";
	private static String hudText = "";
	private static String hudSeconds = "5";
	private static String hudStyle = "titel";
	private static String hudSaveId = "";
	private static String creditsId = "credits_opening";
	private static String creditRole = "";
	private static String creditName = "";
	private static String creditIcon = "";
	private static String creditLink = "";
	private static String creditsNewId = "";
	private static String cueLabel = "";
	private static String cueCommand = "";
	private static String obsHost;
	private static String obsPort;
	private static String obsPassword = "";
	private static final List<String> ICONS = List.of("", "youtube", "spotify", "twitch", "instagram", "tiktok", "discord", "x",
			"soundcloud", "patreon", "github", "web", "mail");
	private static final List<String[]> HUD_STYLES = List.of(new String[] {"titel", "Titel"}, new String[] {"glut", "Glut"},
			new String[] {"kapitel", "Kapitel"}, new String[] {"untertitel", "Untertitel"}, new String[] {"bauchbinde", "Bauchbinde"},
			new String[] {"dialog", "Dialog"}, new String[] {"ziel", "Ziel"}, new String[] {"buch", "Buch"}, new String[] {"boss", "Boss"},
			new String[] {"wabernd", "Wabernd"}, new String[] {"endkarte", "Endkarte"}, new String[] {"camcorder", "Camcorder"});
	private final List<int[]> fieldLabels = new ArrayList<>();
	private final List<String> fieldLabelTexts = new ArrayList<>();
	private static DirectorScreen open;
	private JsonObject state;
	private int page;
	private String status = "";

	public DirectorScreen(JsonObject state) {
		super(Component.literal("Director"));
		this.state = state;
	}

	/** Registers the cue handlers that open/refresh the screen. */
	public static void registerCues() {
		ClientCueDispatcher.register("director_open", (scene, tick, data) -> Minecraft.getInstance().gui.setScreen(new DirectorScreen(data)));
		ClientCueDispatcher.register("director_state", (scene, tick, data) -> {
			if (open != null) {
				open.state = data;
				open.rebuildWidgets();
			}
		});
	}

	@Override
	protected void init() {
		if (open != this) {
			de.behindtheblocks.cinematic.client.obs.ObsBridge.onMenuOpened(); // keep the menu out of the footage
		}
		open = this;
		int panelX = 10, panelY = 10, panelW = width - 20, panelH = height - 20;
		int tabW = Math.min(110, panelW / 5);
		int y = panelY + 34;
		// Tab height adapts to the window so all sections fit even at large GUI scales.
		int step = Math.max(12, Math.min(21, (panelH - 34 - 34) / Tab.values().length));
		int tabH = Math.max(11, step - 2);
		for (Tab t : Tab.values()) {
			Button b = Button.builder(Component.literal((t == currentTab ? "▶ " : "") + t.label), btn -> {
				currentTab = t;
				page = 0;
				rebuildWidgets();
			}).bounds(panelX + 8, y, tabW - 8, tabH).build();
			addRenderableWidget(b);
			y += step;
		}
		int contentX = panelX + tabW + 10, contentW = panelW - tabW - 20;
		int contentTop = panelY + 34, contentBottom = panelY + panelH - 32;
		buildContent(contentX, contentTop, contentW, contentBottom);

		int by = panelY + panelH - 26;
		addRenderableWidget(Button.builder(Component.literal("↻ Aktualisieren"), b -> cmd("btb director refresh"))
				.bounds(panelX + 8, by, 100, 18).build());
		addRenderableWidget(Button.builder(Component.literal("⟲ Set zurücksetzen"), b -> {
			cmd("btb resetall");
			refreshSoon();
		}).tooltip(Tooltip.create(Component.literal("Alle Szenen zurücksetzen, Portale schließen, Zerstörung wiederherstellen, Effekte löschen")))
				.bounds(panelX + 112, by, 120, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> onClose())
				.bounds(panelX + panelW - 88, by, 80, 18).build());
	}

	@Override
	public void removed() {
		if (open == this) {
			open = null;
			clickMode = ClickMode.ON_CLOSE; // never leave a "clicks add instead of run" mode armed
			// Collected actions start now – the menu is gone, so they are in the picture, not the menu.
			if (!PENDING.isEmpty() && Minecraft.getInstance().player != null) {
				for (String c : PENDING) {
					DirectorHotkeys.muteFeedback();
					Minecraft.getInstance().player.connection.sendCommand(c);
				}
				PENDING.clear();
			}
			if (!DirectorHotkeys.isPeeking()) { // still peeking: the recording stays paused until the key is released
				de.behindtheblocks.cinematic.client.obs.ObsBridge.onMenuClosed();
			}
		}
	}

	// ------------------------------------------------------------------ content

	/** A row: label on the left, action buttons on the right. */
	private record Row(String label, int color, List<Action> actions) {
	}

	private record Action(String label, String command, String tooltip, boolean meta) {
		Action(String label, String command, String tooltip) {
			this(label, command, tooltip, false);
		}
	}

	private void buildContent(int x, int top, int w, int bottom) {
		List<Row> rows = new ArrayList<>();
		List<Action> header = new ArrayList<>();
		fieldLabels.clear();
		fieldLabelTexts.clear();
		// Click mode toggle sits top right of every tab.
		int modeW = 104;
		addRenderableWidget(Button.builder(Component.literal(clickMode.label), b -> {
			clickMode = ClickMode.values()[(clickMode.ordinal() + 1) % ClickMode.values().length];
			status = switch (clickMode) {
				case ON_CLOSE -> "Aktionen starten, sobald das Menü zu ist";
				case RUN -> "Klicks führen Aktionen sofort aus (zum Testen)";
				case SCENE -> "Klicks fügen zur Szene '" + builderScene + "' bei " + builderTime + " s hinzu (Baukasten)";
				case CUES -> "Klicks hängen Cues an die Cue-Liste an (Aufnahme)";
				case SLOT -> "Nächster Klick wird auf Numpad " + targetSlot + " gelegt";
			};
			rebuildWidgets();
		}).tooltip(Tooltip.create(Component.literal("Ausführen / zur Szene hinzufügen / zur Cue-Liste hinzufügen")))
				.bounds(x + w - modeW, top - 26, modeW, 16).build());
		if (clickMode == ClickMode.SLOT) {
			addRenderableWidget(Button.builder(Component.literal("Num " + targetSlot + " ▸"), b -> {
				targetSlot = targetSlot % 9 + 1;
				rebuildWidgets();
			}).tooltip(Tooltip.create(Component.literal("Auf welche Taste der nächste Klick gelegt wird")))
					.bounds(x + w - modeW - 52, top - 26, 48, 16).build());
		}
		if (currentTab == Tab.BUILDER || currentTab == Tab.HUD || currentTab == Tab.CREDITS || currentTab == Tab.RECORDING) {
			addRenderableWidget(Button.builder(Component.literal(showForm ? "✎ Eingabe ▴" : "✎ Eingabe ▾"), b -> {
				showForm = !showForm;
				rebuildWidgets();
			}).tooltip(Tooltip.create(Component.literal("Eingabefelder ein-/ausblenden (mehr Platz für die Liste)")))
					.bounds(x + w - modeW - 72 - (clickMode == ClickMode.SLOT ? 52 : 0), top - 26, 68, 16).build());
		}
		if (showForm) {
			top = buildForm(x, top, w);
		}
		switch (currentTab) {
			case SCENES -> {
				header.add(new Action("+ Neue Szene", "local:newscene", "Im Baukasten eine neue Szene anlegen", true));
				header.add(new Action("Alle stoppen", "btb scene stop", null));
				header.add(new Action("Neu laden", "btb scene reload", null));
				for (JsonObject s : objects("scenes")) {
					String id = s.get("id").getAsString(), st = s.get("state").getAsString();
					int color = st.equals("running") ? 0xFF7CFF7C : st.equals("paused") ? 0xFFFFD25A : 0xFFBBBBBB;
					String label = id + "  " + (st.equals("stopped") ? "" : "· " + st + " " + s.get("tick").getAsInt() + "/" + s.get("duration").getAsInt());
					rows.add(new Row(label, color, List.of(
							new Action("▶", "btb scene restart " + id, "Starten / neu starten"),
							new Action(st.equals("paused") ? "⏵" : "⏸", "btb scene " + (st.equals("paused") ? "resume " : "pause ") + id, "Pause / Fortsetzen"),
							new Action("■", "btb scene stop " + id, "Stoppen"),
							new Action("⟲", "btb scene reset " + id, "Zurücksetzen"),
							new Action("✎", "local:edit:" + id, "Im Baukasten bearbeiten (Schritte, Zeiten, auf Taste legen)", true))));
				}
			}
			case BUILDER -> {
				JsonObject ev = state != null && state.has("scene_events") ? state.getAsJsonObject("scene_events") : new JsonObject();
				if (ev.has(builderScene)) {
					JsonArray list = ev.getAsJsonArray(builderScene);
					for (int i = 0; i < list.size(); i++) {
						JsonObject e = list.get(i).getAsJsonObject();
						int nr = i + 1;
						String t = fmt(e.get("t").getAsDouble());
						rows.add(new Row(nr + ".  " + t + " s   " + e.get("label").getAsString(), 0xFFFFFFFF, List.of(
								new Action("▶", "btb scene test " + builderScene + " " + nr, "Nur diesen Schritt jetzt ausführen", true),
								new Action("−1s", "btb scene shift " + builderScene + " " + nr + " -1", "1 Sekunde früher", true),
								new Action("+1s", "btb scene shift " + builderScene + " " + nr + " 1", "1 Sekunde später", true),
								new Action("⏱", "local:time:" + t, "Zeitfeld auf " + t + " s setzen", true),
								new Action("✕", "btb scene remove " + builderScene + " " + nr, "Schritt löschen", true))));
					}
				}
			}
			case CAMERA -> {
				header.add(new Action("Kamera stop", "btb camera stop", null));
				header.add(new Action("1. Person", "btb camera perspective first", null));
				header.add(new Action("3. Person", "btb camera perspective third", null));
				header.add(new Action("Zoom 30°", "btb camera zoom 30 1.5", null));
				header.add(new Action("Zoom ⟲", "btb camera zoom reset", null));
				header.add(new Action("● Pfad aufnehmen", "btb camera record take_" + (System.currentTimeMillis() / 1000 % 100000), "Fahrt fliegen, dann 'Aufnahme stop'"));
				header.add(new Action("■ Aufnahme stop", "btb camera record stop", null));
				rows.add(new Row("Handkamera", 0xFFFFFFFF, List.of(new Action("Ruhig", "btb camera handheld 0.5", "leichtes Atmen der Kamera"),
						new Action("Doku", "btb camera handheld 1", null), new Action("Lauf", "btb camera handheld 1.8", null),
						new Action("Aus", "btb camera handheld off", null))));
				rows.add(new Row("Dolly-Zoom (Vertigo) auf den Block vor dir", 0xFFFFFFFF, List.of(
						new Action("Ran", "execute anchored eyes positioned ^ ^ ^6 run btb camera dollyzoom ~ ~ ~ 14 4 4", "Kamera fährt ran, Hintergrund weitet sich"),
						new Action("Weg", "execute anchored eyes positioned ^ ^ ^6 run btb camera dollyzoom ~ ~ ~ 4 14 4", "Kamera fährt weg, Hintergrund rückt heran"))));
				rows.add(new Row("Bild-im-Bild: Sicht des nächsten Darstellers", 0xFFFFFFFF, List.of(
						new Action("Oben rechts", "btb pip @p[tag=!kamera,tag=!pip,distance=0.1..] tr 0.3", "zweites Fenster mit Tag pip + OBS-Quelle „BTB PiP“"),
						new Action("Unten links", "btb pip @p[tag=!kamera,tag=!pip,distance=0.1..] bl 0.3", null),
						new Action("Aus", "btb pip off", null))));
				rows.add(new Row("Replay „take“ (einmal spielen, beliebig oft filmen)", 0xFFFF9070, List.of(
						new Action("● Aufnehmen", "btb replay record take", "alle Darsteller, Kreaturen, Szenen und Ereignisse"),
						new Action("■ Stopp", "btb replay stop", null),
						new Action("▶ Abspielen", "btb replay play take", "Darsteller werden kurz Zuschauer, Puppen spielen"),
						new Action("✂ Editor", "btb replay editor", "Timeline wie ReplayMod: spulen, Kamera-Keyframes, Export (F6)"))));
				rows.add(new Row("Film-Export (bildgenau, ohne Ruckler)", 0xFFFF9070, List.of(
						new Action("4K/60 · 10 s", "btb export take_4k 10 4k 60 take", "Replay „take“ als 4K-Video, 60 FPS"),
						new Action("1080p/60 · 10 s", "btb export take_1080 10 1080p 60 take", null),
						new Action("Abbrechen", "btb export stop", null))));
				rows.add(new Row("Ego-Kamera (Bodycam des nächsten Darstellers)", 0xFFFFFFFF, List.of(
						new Action("An", "btb camera ego @p[tag=!kamera,distance=0.1..]", "Kopf wippt beim Gehen, dreht etwas nach, atmet"),
						new Action("Aus", "btb camera stop", null))));
				rows.add(new Row("Sicht-Schnitt (Augen des Darstellers)", 0xFFFFFFFF, List.of(
						new Action("3 s", "btb camera pov @p[tag=!kamera,distance=0.1..] 3", "Aufnahme-Kamera schaut kurz durch seine Augen"),
						new Action("8 s", "btb camera pov @p[tag=!kamera,distance=0.1..] 8", null))));
				rows.add(new Row("Zeitlupe", 0xFFFFFFFF, List.of(new Action("½ 3 s", "btb slowmo 0.5 3", null),
						new Action("¼ 3 s", "btb slowmo 0.25 3", null), new Action("Aus", "btb slowmo off", null))));
				for (String id : strings("cameras")) {
					rows.add(new Row(id, 0xFFFFFFFF, List.of(
							new Action("▶ ich", "btb camera play " + id, "Fahrt für mich abspielen"),
							new Action("▶ alle", "btb camera play " + id + " @a", "Fahrt für alle Spieler"),
							new Action("+ Punkt", "btb camera point " + id, "Aktuelle Ansicht als neuen Punkt anhängen"),
							new Action("ⓘ", "btb camera info " + id, "Punkte im Chat anzeigen"))));
				}
			}
			case EFFECTS -> {
				rows.add(new Row("Blenden", 0xFFFFFFFF, List.of(new Action("Weiß", "btb effects fade white 1.5", null),
						new Action("Schwarz", "btb effects fade black 1.5", null), new Action("Auf", "btb effects fadeout 1.5", null))));
				rows.add(new Row("Licht & Bewegung", 0xFFFFFFFF, List.of(new Action("Blitz", "btb effects flash white", null),
						new Action("Lava-Blitz", "btb effects flash orange", null), new Action("Shake", "btb effects shake 1.5 1.5", null))));
				rows.add(new Row("Vignette", 0xFFFFFFFF, List.of(new Action("An", "btb effects vignette 0.85 1", null),
						new Action("Aus", "btb effects vignette off 1", null))));
				rows.add(new Row("Kinobalken", 0xFFFFFFFF, List.of(new Action("An", "btb effects letterbox 0.12 1", null),
						new Action("Aus", "btb effects letterbox off 1", null))));
				rows.add(new Row("Unschärfe", 0xFFFFFFFF, List.of(new Action("An", "btb effects blur 6 1", null),
						new Action("Aus", "btb effects blur off 1", null))));
				rows.add(new Row("Nether-Tönung", 0xFFFFFFFF, List.of(new Action("An", "btb effects tint nether_red 0.35 2", null),
						new Action("Aus", "btb effects tint off 2", null))));
				rows.add(new Row("Tiefenschärfe", 0xFFFFFFFF, List.of(new Action("Auto", "btb effects dof auto 0.6 1", "Schärfe folgt Darsteller / Blickpunkt"),
						new Action("Nah 3", "btb effects dof 3 0.7 1", null), new Action("Fern 20", "btb effects dof 20 0.6 1", null),
						new Action("Aus", "btb effects dof off 1", null))));
				rows.add(new Row("Anamorph-Streifen", 0xFFFFFFFF, List.of(new Action("An", "btb effects anamorph 0.7 1", "lange Lichtstreifen an hellen Lichtern"),
						new Action("Aus", "btb effects anamorph off 1", null))));
				rows.add(new Row("Rückblende", 0xFFFFFFFF, List.of(new Action("An", "btb effects flashback 0.7 1.5", null),
						new Action("Aus", "btb effects flashback off 1.5", null))));
				rows.add(new Row("Bildformat", 0xFFFFFFFF, List.of(new Action("2.39", "btb effects format 2.39 1", null),
						new Action("1.85", "btb effects format 1.85 1", null), new Action("4:3", "btb effects format 4x3 1", null),
						new Action("Aus", "btb effects format off 1", null))));
				rows.add(new Row("Farbkorrektur (LUT)", 0xFFFFFFFF, List.of(new Action("Nether", "btb effects lut nether 0.8 2", null),
						new Action("Film", "btb effects lut film 0.8 2", null), new Action("Kalt", "btb effects lut kalt 0.8 2", null),
						new Action("Bleach", "btb effects lut bleach 0.8 2", null), new Action("Aus", "btb effects lut off 2", null))));
				rows.add(new Row("Fokus-Zug", 0xFFFFFFFF, List.of(new Action("Scharf ziehen", "btb effects focus 2.5 6", "unscharf → scharf"),
						new Action("Raus", "btb effects focus 2 6 out", "scharf → unscharf"), new Action("Aus", "btb effects blur off 1", null))));
				rows.add(new Row("Filmkorn", 0xFFFFFFFF, List.of(new Action("Leicht", "btb effects grain 0.3 2", null),
						new Action("Mittel", "btb effects grain 0.6 2", null), new Action("Stark", "btb effects grain 1 2", null),
						new Action("Aus", "btb effects grain off 2", null))));
				rows.add(new Row("Hitzeflimmern", 0xFFFFFFFF, List.of(new Action("Leicht", "btb effects haze 0.3 2", null),
						new Action("Stark", "btb effects haze 0.9 2", null), new Action("Aus", "btb effects haze off 2", null))));
				rows.add(new Row("Ton dumpf (nach Explosion)", 0xFFFFFFFF, List.of(new Action("Dumpf 6 s", "btb effects muffle 6 0.85", null),
						new Action("+ Pfeifen", "btb sound play ear_ring 0.7 1", null), new Action("Stille", "btb sound volume 0.1", "Kino-Ton leiser vor dem großen Moment"),
						new Action("Ton zurück", "btb sound volume 1", null))));
				rows.add(new Row("Übergänge", 0xFFFFFFFF, List.of(new Action("Iris zu", "btb effects transition iris_close black 1.2", null),
						new Action("Wipe", "btb effects transition wipe_left black 1", null), new Action("Alles weg", "btb effects clear 1", null))));
			}
			case HUD -> {
				for (String id : strings("hud")) {
					rows.add(new Row(id, 0xFFFFFFFF, List.of(new Action("Zeigen", "btb hud show " + id, null),
							new Action("Weg", "btb hud hide " + id, null))));
				}
			}
			case CREDITS -> {
				JsonObject cr = state != null && state.has("credits") ? state.getAsJsonObject("credits") : new JsonObject();
				if (cr.has(creditsId)) {
					JsonArray en = cr.getAsJsonObject(creditsId).getAsJsonArray("entries");
					for (int i = 0; i < en.size(); i++) {
						rows.add(new Row((i + 1) + ". " + en.get(i).getAsString(), 0xFFFFE0C0, List.of(
								new Action("✕", "btb hud credits " + creditsId + " remove " + (i + 1), "Eintrag löschen", true))));
					}
				}
				rows.add(new Row("Opening-Szene (Credits + Titel)", 0xFFFFFFFF, List.of(new Action("▶", "btb scene restart opening", null),
						new Action("■", "btb scene stop opening", null))));
				rows.add(new Row("Ending-Szene (Endkarte + Abspann)", 0xFFFFFFFF, List.of(new Action("▶", "btb scene restart ending", null),
						new Action("■", "btb scene stop ending", null))));
				rows.add(new Row("Vorspann", 0xFFFFFFFF, List.of(new Action("Zeigen", "btb hud show credits_opening", null),
						new Action("Weg", "btb hud hide credits_opening", null))));
				rows.add(new Row("Abspann (Roll)", 0xFFFFFFFF, List.of(new Action("Zeigen", "btb hud show credits_roll", null),
						new Action("Weg", "btb hud hide credits_roll", null))));
				rows.add(new Row("Endkarte", 0xFFFFFFFF, List.of(new Action("Zeigen", "btb hud show end_card", null),
						new Action("Weg", "btb hud hide end_card", null))));
				rows.add(new Row("Buch / Boss / Nachricht", 0xFFFFFFFF, List.of(new Action("Buch", "btb hud show book_heart", null),
						new Action("Boss", "btb hud show boss_intro", null), new Action("Nachricht", "btb hud show heart_message_ethereal", null))));
			}
			case ACTION -> {
				header.add(new Action("Gegner weg", "btb spawn clear", "Alle gespawnten Gegner entfernen"));
				header.add(new Action("Trigger scharf", "btb trigger rearm", null));
				rows.add(new Row("Requisiten", 0xFFFFE0C0, List.of(new Action("Regieklappe", "give @s btb_cinematic:clapperboard", "Klick = Klappe + Take, Schleichen+Klick = Set zurücksetzen"),
						new Action("Glutfackeln", "give @s btb_cinematic:ember_flare 16", "Licht + roter Rauch, wo du hinschaust"),
						new Action("Seelenkompass", "btb kompass", "zeigt immer zum Herzen"))));
				rows.add(new Row("Markierungen (nur Crew sieht sie)", 0xFFFFE0C0, List.of(
						new Action("Hier setzen", "btb mark add m" + (System.currentTimeMillis() / 1000 % 10000) + " yellow", "Markierung an deiner Position"),
						new Action("Alle weg", "btb mark clear", null), new Action("Aus", "btb mark hide", null), new Action("An", "btb mark show", null))));
				rows.add(new Row("Welt-Ereignisse", 0xFFFFE0C0, List.of(
						new Action("Lava-Ausbruch", "execute positioned ^ ^ ^10 run btb ausbruch ~ ~-1 ~ 8", "10 Blöcke vor dir"),
						new Action("Ghast-Absturz", "execute positioned ^ ^25 ^30 run btb ghastcrash ~ ~ ~", "stürzt vor dir in deine Blickrichtung"),
						new Action("Buch", "give @s btb_cinematic:heart_book", "Das alte Buch"))));
				rows.add(new Row("Wind", 0xFFFFE0C0, List.of(new Action("Leicht", "btb wind ~ 0.3", null),
						new Action("Stark", "btb wind ~ 0.8", null), new Action("Aus", "btb wind off", null))));
				rows.add(new Row("Statisten & Gesten (nächste Figur)", 0xFFFFE0C0, List.of(
						new Action("+ Statist", "execute positioned ^ ^ ^3 run btb statist Statist", "Figur mit Spieler-Skin"),
						new Action("Winken", "btb geste @e[type=minecraft:mannequin,limit=1,sort=nearest] winken 4", null),
						new Action("Zielen", "btb geste @e[type=minecraft:mannequin,limit=1,sort=nearest] zielen 5", null),
						new Action("Ducken", "btb geste @e[type=minecraft:mannequin,limit=1,sort=nearest] ducken 5", null),
						new Action("Weg", "btb statist clear", null))));
				rows.add(new Row("Kreaturen", 0xFFFFE0C0, List.of(
						new Action("Glutlinge", "execute positioned ^ ^2 ^6 run btb spawn btb_cinematic:glutling 5 ~ ~ ~ 3", "Glutgeister, schwirren durch Wände"),
						new Action("Kriecher", "execute positioned ^ ^ ^6 run btb spawn btb_cinematic:aschekriecher 4 ~ ~ ~ 3", "Aschekriecher, klettern an Wänden"),
						new Action("Wisps", "execute positioned ^ ^2 ^4 run btb spawn btb_cinematic:seelenwisp 3 ~ ~ ~ 3", "harmlose Seelenlichter"))));
				rows.add(new Row("Portalraum (Blöcke)", 0xFFFFE0C0, List.of(
						new Action("Portalfokus", "give @s btb_cinematic:portal_focus 4", "schwebender Kristall mit Ringen"),
						new Action("Runensäule", "give @s btb_cinematic:rune_pillar 32", null),
						new Action("Glyphen", "give @s btb_cinematic:glyph_plate 32", "Bodenplatte mit Glyphenkreis"))));
				rows.add(new Row("Licht & Atmosphäre (Blöcke)", 0xFFFFE0C0, List.of(
						new Action("Kessel", "give @s btb_cinematic:brazier 16", "Glutkessel"),
						new Action("Leuchter", "give @s btb_cinematic:chandelier 16", "Kettenleuchter (an die Decke)"),
						new Action("Lichtsäule", "give @s btb_cinematic:light_shaft 64", "übereinander stapeln = Lichtstrahl"),
						new Action("Glutriss", "give @s btb_cinematic:ember_crack 64", null),
						new Action("Nebel", "give @s btb_cinematic:fog_vent 16", "Nebelquelle in den Boden"),
						new Action("Kristall", "give @s btb_cinematic:soul_crystal_lamp 16", "Seelenkristall-Leuchte"))));
				for (JsonObject z : objects("ambience")) {
					String id = z.get("id").getAsString();
					boolean on = z.get("enabled").getAsBoolean();
					rows.add(new Row("Ambiente " + id + (on ? "  · an" : "  · aus"), on ? 0xFFFF9070 : 0xFF888888, List.of(
							new Action(on ? "Aus" : "An", "btb ambience " + (on ? "off " : "on ") + id, "Ganze Zone (Klang, Partikel, Beben, Blitze)"),
							new Action("Beben aus", "btb ambience shake " + id + " 0 4", "Nur das Wackeln abschalten"),
							new Action("Blitze aus", "btb ambience flash " + id + " off", "Nur die roten Lichtblitze abschalten"),
							new Action("Partikel aus", "btb ambience particles " + id + " clear", "Partikel (z. B. roter Strahl) abschalten"))));
				}
				rows.add(new Row("Boss: Herzwächter (lässt den Schlüssel fallen)", 0xFFFF7A40, List.of(
						new Action("Hier", "btb spawn btb_cinematic:herzwaechter 1 ^ ^ ^8 0", "Erscheint 8 Blöcke vor dir und greift an"),
						new Action("Steht still", "btb spawn btb_cinematic:herzwaechter 1 ^ ^ ^8 0 still", "Für Aufnahmen: bewegt sich nicht"))));
				rows.add(new Row("Welle: Piglins (5)", 0xFFFFFFFF, List.of(new Action("Hier", "btb spawn zombified_piglin 5", null))));
				rows.add(new Row("Welle: Skelette (4)", 0xFFFFFFFF, List.of(new Action("Hier", "btb spawn wither_skeleton 4", null))));
				rows.add(new Row("Ghast am Horizont", 0xFFFFFFFF, List.of(new Action("Hier", "btb spawn ghast 1 ~ ~25 ~ 12", null))));
				// Mutant Monsters (optional mod): bosses for the big fights. Only shown when installed.
				if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mutantmonsters")) {
					for (String[] m : new String[][] {{"Mutant-Zombie", "mutant_zombie"}, {"Mutant-Skelett", "mutant_skeleton"},
							{"Mutant-Creeper", "mutant_creeper"}, {"Mutant-Enderman", "mutant_enderman"}}) {
						rows.add(new Row("Boss: " + m[0], 0xFFFF7A40, List.of(
								new Action("1×", "btb spawn mutantmonsters:" + m[1] + " 1 ^ ^ ^8 0", "Erscheint 8 Blöcke vor dir"),
								new Action("3×", "btb spawn mutantmonsters:" + m[1] + " 3 ^ ^ ^10 5", null))));
					}
				}
				for (JsonObject c : objects("parkour")) {
					String id = c.get("id").getAsString();
					boolean on = c.get("running").getAsBoolean();
					rows.add(new Row("Parcours " + id + (on ? "  · läuft" : ""), on ? 0xFF7CFF7C : 0xFFFFFFFF, List.of(
							new Action("Start", "btb parkour start " + id, null), new Action("Stop", "btb parkour stop " + id, null))));
				}
				for (String id : strings("triggers")) {
					rows.add(new Row("Trigger " + id, 0xFFFFD25A, List.of(new Action("Auslösen", "btb trigger fire " + id, null),
							new Action("An", "btb trigger enable " + id, null), new Action("Aus", "btb trigger disable " + id, null))));
				}
			}
			case RECORDING -> {
				String obs = de.behindtheblocks.cinematic.client.obs.ObsBridge.status();
				rows.add(new Row("OBS: " + obs, obs.startsWith("verbunden") ? 0xFF7CFF7C : 0xFFBBBBBB, List.of(
						new Action("● Start", "obs:start", null), new Action("■ Stop", "obs:stop", null),
						new Action("⏸", "obs:pause", null), new Action("⏵", "obs:resume", null))));
				rows.add(new Row("Clean Feed (F8)", 0xFFFFFFFF, List.of(new Action("Umschalten", "local:cleanfeed", "Vanilla-HUD für die Aufnahme ausblenden"))));
				rows.add(new Row("Cue-Liste (Numpad Enter = GO, Numpad + = anzeigen)", 0xFFFFFFFF, List.of(
						new Action("An den Anfang", "local:cuepointer:0", null, true), new Action("Leeren", "local:cueclear", "Alle Cues löschen", true))));
				rows.add(new Row("Tasten beim Filmen (Numpad 1–9) – belegen: oben „Klick → Taste“", 0xFFFFFFFF, List.of()));
				for (int n = 1; n <= 9; n++) {
					String l = DirectorHotkeys.slotLabel(n);
					rows.add(new Row("   Num " + n + "   " + (l.isEmpty() ? "§8(frei)" : l), l.isEmpty() ? 0xFF888888 : 0xFF9CE0FF, List.of(
							new Action("▶", "local:slotfire:" + n, "Jetzt auslösen (Test)", true),
							new Action("Belegen", "local:slottarget:" + n, "Nächsten Klick auf diese Taste legen", true),
							new Action("✕", "local:slotclear:" + n, "Taste freigeben", true))));
				}
				List<DirectorHotkeys.Cue> cues = DirectorHotkeys.cues();
				for (int i = 0; i < cues.size(); i++) {
					boolean next = i == DirectorHotkeys.pointer();
					rows.add(new Row((next ? "▶ " : "   ") + (i + 1) + ". " + cues.get(i).label(), next ? 0xFFFFC080 : 0xFFDDDDDD, List.of(
							new Action("↑", "local:cueup:" + i, null, true), new Action("↓", "local:cuedown:" + i, null, true),
							new Action("Ab hier", "local:cuepointer:" + i, "GO startet bei diesem Cue", true),
							new Action("✕", "local:cuedel:" + i, null, true))));
				}
			}
			case SOUND -> {
				header.add(new Action("Alles stop", "btb sound stop", null));
				header.add(new Action("Musik", "btb sound music nether_drone 3", null));
				header.add(new Action("Musik aus", "btb sound music stop 3", null));
				rows.add(new Row("Hall", 0xFFFFFFFF, List.of(new Action("Aus", "btb sound reverb off", null),
						new Action("Höhle", "btb sound reverb cave 0.7", null), new Action("Halle", "btb sound reverb hall 0.6", null),
						new Action("Nether", "btb sound reverb nether 0.7", null))));
				for (String id : strings("sounds")) {
					rows.add(new Row(id, 0xFFFFFFFF, List.of(new Action("▶", "btb sound play " + id, null))));
				}
			}
			case SHOWCASE -> {
				header.add(new Action("Alle stoppen", "btb showcase stop", null));
				for (JsonObject s : objects("showcases")) {
					String id = s.get("id").getAsString();
					boolean running = s.get("running").getAsBoolean();
					rows.add(new Row(id + (running ? "  · läuft" : ""), running ? 0xFF7CFF7C : 0xFFFFFFFF, List.of(
							new Action("▶", "btb showcase play " + id, "Mit gespeicherter Animation"),
							new Action("Reveal", "btb showcase play " + id + " reveal", null),
							new Action("Puls", "btb showcase play " + id + " pulse", null),
							new Action("Explode", "btb showcase play " + id + " explode", null),
							new Action("■", "btb showcase stop " + id, null))));
				}
			}
			case PORTALS -> {
				header.add(new Action("Alle schließen", "btb portal deactivate", null));
				for (JsonObject s : objects("portals")) {
					String id = s.get("id").getAsString(), st = s.get("state").getAsString();
					rows.add(new Row(id + "  · " + st, st.equals("INACTIVE") ? 0xFFBBBBBB : 0xFFFF7AE0, List.of(
							new Action("Aktivieren", "btb portal activate " + id, "Komplette Aktivierungssequenz"),
							new Action("Weiß", "btb portal style " + id + " white", "Epischer weißer Stil"),
							new Action("Sofort", "btb portal activate " + id + " instant", "Direkt offen"),
							new Action("Schließen", "btb portal deactivate " + id, null))));
				}
			}
			case DESTRUCTION -> {
				header.add(new Action("Alles wiederherstellen", "btb destruction restore", null));
				for (String id : strings("destruction")) {
					rows.add(new Row(id, 0xFFFFFFFF, List.of(new Action("Auslösen", "btb destruction trigger " + id, null),
							new Action("Explosion", "btb destruction trigger " + id + " explode", null),
							new Action("Lava", "btb destruction trigger " + id + " lava", null),
							new Action("⟲", "btb destruction restore " + id, "Aus dem Backup wiederherstellen"))));
				}
			}
			case PLAYERS -> {
				header.add(new Action("Alle zu mir", "tp @a @s", null));
				header.add(new Action("Alle Zuschauer", "gamemode spectator @a", null));
				for (String name : strings("players")) {
					rows.add(new Row(name, 0xFFFFFFFF, List.of(new Action("Zu mir", "tp " + name + " @s", null),
							new Action("Hin", "tp @s " + name, null),
							new Action("Auto-Regie", "btb camera auto " + name + " 6", "Perspektive wechselt alle 6 s: Schulter, Seite, Front, Kran, Untersicht, Verfolger, Kreisen"),
							new Action("Schulter", "btb camera follow " + name + " shoulder 3 1", null),
							new Action("Front", "btb camera follow " + name + " front 5 1", "Von vorne aufs Gesicht"),
							new Action("Kran", "btb camera follow " + name + " crane 9 2", "Hoch oben, langsam kreisend"),
							new Action("Seite", "btb camera follow " + name + " side 9 2 +z", "2.5D-Seitenansicht (Jump'n'Run)"),
							new Action("Kreativ", "gamemode creative " + name, null),
							new Action("Zuschauer", "gamemode spectator " + name, null))));
				}
			}
		}

		// Header actions.
		int hx = x, hy = top;
		for (Action a : header) {
			int bw = font.width(a.label) + 14;
			if (hx > x && hx + bw > x + w) { // wrap header buttons in small windows
				hx = x;
				hy += 22;
			}
			addRenderableWidget(button(a, hx, hy, bw));
			hx += bw + 4;
		}
		int headerBottom = hy + 24;
		int listTop = top + (header.isEmpty() ? 0 : headerBottom - top);
		// Rows with more buttons than fit next to their label continue on an extra line (small windows).
		List<Row> fitted = new ArrayList<>();
		int minLabel = Math.min(110, w / 3);
		for (Row r : rows) {
			List<Action> line = new ArrayList<>();
			int used = 0;
			boolean first = true;
			for (int k = r.actions.size() - 1; k >= 0; k--) { // buttons are laid out from the right
				Action act = r.actions.get(k);
				int bw = Math.max(20, font.width(act.label) + 12) + 3;
				int room = w - (first ? Math.min(minLabel, font.width(r.label) + 12) : 8);
				if (!line.isEmpty() && used + bw > room) {
					java.util.Collections.reverse(line);
					fitted.add(new Row(first ? r.label : "", r.color, List.copyOf(line)));
					line.clear();
					used = 0;
					first = false;
				}
				line.add(act);
				used += bw;
			}
			java.util.Collections.reverse(line);
			// the label goes on the first line, i.e. the one holding the rightmost buttons; keep order: label line first
			fitted.add(new Row(first ? r.label : "", r.color, List.copyOf(line)));
		}
		rows = fitted;
		int perPage = Math.max(1, (bottom - listTop - 22) / ROW_H);
		int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		this.rows = new ArrayList<>();
		int y = listTop;
		for (int i = page * perPage; i < Math.min(rows.size(), (page + 1) * perPage); i++) {
			Row r = rows.get(i);
			int bx = x + w;
			for (int k = r.actions.size() - 1; k >= 0; k--) {
				Action a = r.actions.get(k);
				int bw = Math.max(20, font.width(a.label) + 12);
				bx -= bw;
				addRenderableWidget(button(a, r.label.trim() + ": " + a.label, bx, y + 1, bw));
				bx -= 3;
			}
			this.rows.add(new PlacedRow(r, x, y, bx - x - 8));
			y += ROW_H;
		}
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			}).bounds(x, bottom - 20, 20, 18).build());
			addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				page = Math.min(pages - 1, page + 1);
				rebuildWidgets();
			}).bounds(x + 24, bottom - 20, 20, 18).build());
			pageLabel = "Seite " + (page + 1) + "/" + pages;
			pageLabelPos = new int[] {x + 50, bottom - 15};
		} else {
			pageLabel = null;
		}
		if (rows.isEmpty()) {
			emptyHint = new int[] {x, listTop + 4};
		} else {
			emptyHint = null;
		}
	}


	// ------------------------------------------------------------------ forms (typing instead of files)

	private EditBox field(int x, int y, int w, String value, java.util.function.Consumer<String> setter, String hint, int max) {
		EditBox box = new EditBox(font, x, y, w, 16, Component.literal(hint));
		box.setMaxLength(max);
		box.setValue(value == null ? "" : value);
		// The vanilla hint is not clipped: shorten it to the box.
		String h = font.width(hint) > w - 8 ? font.plainSubstrByWidth(hint, Math.max(4, w - 8 - font.width("…"))) + "…" : hint;
		box.setHint(Component.literal(h).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		box.setResponder(setter);
		addRenderableWidget(box);
		return box;
	}

	private void label(int x, int y, String text) {
		fieldLabels.add(new int[] {x, y + 4});
		fieldLabelTexts.add(text);
	}

	private Button small(String text, int x, int y, int w, String tooltip, Runnable action) {
		Button.Builder b = Button.builder(Component.literal(text), btn -> action.run()).bounds(x, y, w, 16);
		if (tooltip != null) {
			b.tooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return addRenderableWidget(b.build());
	}

	private static String quote(String s) {
		return s.replace("\n", "|").trim();
	}

	/** Left-to-right layout that wraps to the next line when the panel is too narrow. */
	private final class Flow {
		final int x0, maxX;
		int cx, cy;

		Flow(int x, int y, int w) {
			x0 = x;
			maxX = x + w;
			cx = x;
			cy = y;
		}

		private int place(int w) {
			if (cx > x0 && cx + w > maxX) {
				cx = x0;
				cy += 20;
			}
			int at = cx;
			cx += w + 4;
			return at;
		}

		Flow label(String text) {
			int w = font.width(text);
			int at = place(w);
			DirectorScreen.this.label(at, cy, text);
			return this;
		}

		Flow button(String text, String tooltip, Runnable action) {
			int w = font.width(text) + 12;
			small(text, place(w), cy, w, tooltip, action);
			return this;
		}

		Flow field(int w, String value, java.util.function.Consumer<String> setter, String hint, int max) {
			int fw = Math.min(w, maxX - x0);
			DirectorScreen.this.field(place(fw), cy, fw, value, setter, hint, max);
			return this;
		}

		/** A field that takes the rest of the line (at least min). */
		Flow fill(int reserve, int min, String value, java.util.function.Consumer<String> setter, String hint, int max) {
			int fw = Math.max(min, maxX - cx - reserve);
			return field(fw, value, setter, hint, max);
		}

		Flow newline() {
			cx = x0;
			cy += 20;
			return this;
		}

		int end() {
			return cy + 22;
		}
	}

	private int hint(int x, int y, int w, String text) {
		for (var line : font.split(Component.literal(text), w)) {
			fieldLabels.add(new int[] {x, y});
			StringBuilder sb = new StringBuilder();
			line.accept((i, style, cp) -> {
				sb.appendCodePoint(cp);
				return true;
			});
			fieldLabelTexts.add("§7" + sb);
			y += 10;
		}
		return y + 2;
	}

	/** Builds the input area of the current tab and returns where the list starts. */
	private int buildForm(int x, int top, int w) {
		switch (currentTab) {
			case BUILDER -> {
				List<String> ids = objects("scenes").stream().map(o -> o.get("id").getAsString()).toList();
				String rec = state != null && state.has("recording") ? state.get("recording").getAsString() : null;
				Flow f = new Flow(x, top, w);
				f.label("Szene").button("◀", "Vorherige Szene", () -> cycleScene(ids, -1))
						.field(110, builderScene, v -> builderScene = v.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), "name", 40)
						.button("▶", "Nächste Szene", () -> cycleScene(ids, 1))
						.button("Neu", "Leere Szene anlegen (30 s)", () -> perform("btb scene create " + builderScene, null, true))
						.button("▶ Abspielen", null, () -> perform("btb scene restart " + builderScene, null, true))
						.button("■", "Stoppen", () -> perform("btb scene stop " + builderScene, null, true));
				if (rec == null) {
					f.button("● Live aufnehmen", "Ab jetzt landet jede Aktion (Klick, Befehl, Cue) mit genauer Zeit in der Szene. "
							+ "Menü schließen und loslegen.", () -> perform("btb scene rec " + builderScene, null, true));
				} else {
					f.button("■ Aufnahme stoppen", "Nimmt gerade in '" + rec + "' auf", () -> perform("btb scene rec stop", null, true));
				}
				String cur = DirectorHotkeys.slotLabel(targetSlot);
				f.button("⌨ Auf Num " + targetSlot, "Szene auf diese Numpad-Taste legen: beim Filmen ein Druck, und alles läuft ab."
						+ (cur.isEmpty() ? "" : "\nErsetzt: " + cur), () -> {
					DirectorHotkeys.setSlot(targetSlot, "Szene " + builderScene, "btb scene restart " + builderScene);
					status = "Numpad " + targetSlot + " startet jetzt die Szene „" + builderScene + "“";
					rebuildWidgets();
				}).button("▸", "Andere Taste wählen", () -> {
					targetSlot = targetSlot % 9 + 1;
					rebuildWidgets();
				});
				f.button("Löschen", "Szene komplett löschen", () -> perform("btb scene delete " + builderScene, null, true));
				f.newline().label("Bei").field(34, builderTime, v -> builderTime = v.replace(',', '.'), "s", 8).label("s")
						.fill(96, 120, builderCommand, v -> builderCommand = v, "Befehl, z. B. btb effects fade black 2", 250)
						.button("+ Hinzufügen", "Befehl als Schritt in die Szene", () -> {
							if (!builderCommand.isBlank()) {
								perform("btb scene add " + builderScene + " " + time() + " " + strip(builderCommand), null, true);
							}
						});
				JsonObject evs = state != null && state.has("scene_events") ? state.getAsJsonObject("scene_events") : new JsonObject();
				if (evs.has(builderScene) && !evs.getAsJsonArray(builderScene).isEmpty()) {
					return f.end();
				}
				return hint(x, f.end(), w, "Tipp: oben rechts „Klick → Szene“ wählen und in den anderen Reitern klicken – "
						+ "alles landet bei der Zeit „Bei“. Oder „Live aufnehmen“ und einfach inszenieren.");
			}
			case HUD -> {
				Flow f = new Flow(x, top, w);
				f.label("Text").fill(70, 120, hudText, v -> hudText = v, "Text … | neue Zeile  (Name | Untertitel)", 250)
						.field(30, hudSeconds, v -> hudSeconds = v.replace(',', '.'), "s", 6).label("Sek.").newline();
				for (String[] st : HUD_STYLES) {
					boolean sel = st[0].equals(hudStyle);
					f.button((sel ? "▸" : "") + st[1], "Zeigen als " + st[1] + " (0 Sek. = bleibt stehen)", () -> {
						hudStyle = st[0];
						if (hudText.isBlank()) {
							status = "Erst Text eingeben";
							rebuildWidgets();
							return;
						}
						perform("btb hud text " + st[0] + " " + seconds(hudSeconds, "5") + " " + quote(hudText), st[1] + ": " + hudText, false);
						rebuildWidgets();
					});
				}
				f.newline().label("Als Vorlage")
						.field(100, hudSaveId, v -> hudSaveId = v.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), "name", 40)
						.button("Speichern", "Text im gewählten Stil speichern (erscheint unten in der Liste)", () -> {
							if (!hudSaveId.isBlank() && !hudText.isBlank()) {
								perform("btb hud save " + hudSaveId + " " + hudStyle + " " + seconds(hudSeconds, "5") + " " + quote(hudText), null, true);
							}
						})
						.button("Alle weg", null, () -> perform("btb hud hide", "HUD ausblenden", false));
				return f.end();
			}
			case CREDITS -> {
				List<String> ids = new ArrayList<>();
				if (state != null && state.has("credits")) {
					ids.addAll(state.getAsJsonObject("credits").keySet());
				}
				String kind = state != null && state.has("credits") && state.getAsJsonObject("credits").has(creditsId)
						? state.getAsJsonObject("credits").getAsJsonObject(creditsId).get("style").getAsString() : "?";
				Flow f = new Flow(x, top, w);
				f.label("Liste").button("◀", null, () -> {
					creditsId = cycle(ids, creditsId, -1);
					rebuildWidgets();
				}).label("§f" + creditsId + " §7(" + kind + ")").button("▶", null, () -> {
					creditsId = cycle(ids, creditsId, 1);
					rebuildWidgets();
				}).button("Vorschau", null, () -> perform("btb hud show " + creditsId, null, true))
						.button("Leeren", "Alle Einträge löschen", () -> perform("btb hud credits " + creditsId + " clear", null, true));
				f.newline().label("Rolle").field(90, creditRole, v -> creditRole = v, "Regie", 80)
						.label("Name").field(90, creditName, v -> creditName = v, "Name", 80)
						.button("Icon: " + (creditIcon.isEmpty() ? "–" : creditIcon), "Klicken zum Wechseln", () -> {
							creditIcon = ICONS.get((ICONS.indexOf(creditIcon) + 1) % ICONS.size());
							rebuildWidgets();
						})
						.fill(0, 110, creditLink, v -> creditLink = v, "Link", 120);
				f.newline().button("+ Eintrag", "Rolle | Name | Icon | Link hinzufügen", () -> {
					if (!creditRole.isBlank() || !creditName.isBlank() || !creditLink.isBlank()) {
						perform("btb hud credits " + creditsId + " add " + creditRole.trim() + " | " + creditName.trim() + " | " + creditIcon
								+ " | " + creditLink.trim(), null, true);
					}
				}).button("+ Überschrift", "Text aus „Rolle“ als Überschrift", () -> {
					if (!creditRole.isBlank()) {
						perform("btb hud credits " + creditsId + " heading " + creditRole.trim(), null, true);
					}
				}).button("Titel setzen", "Text aus „Rolle“ als Titel (Abspann / Endkarte: Titel | Untertitel)", () -> {
					if (!creditRole.isBlank()) {
						perform("btb hud credits " + creditsId + " title " + creditRole.trim(), null, true);
					}
				});
				f.label("Neu:").field(80, creditsNewId, v -> creditsNewId = v.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), "name", 40);
				for (String k : List.of("vorspann", "abspann", "endkarte")) {
					f.button(k, "Neue " + k + "-Liste anlegen", () -> {
						if (!creditsNewId.isBlank()) {
							creditsId = creditsNewId;
							perform("btb hud credits " + creditsNewId + " new " + k, null, true);
						}
					});
				}
				return f.end();
			}
			case RECORDING -> {
				if (obsHost == null) {
					obsHost = de.behindtheblocks.cinematic.client.obs.ObsBridge.host();
					obsPort = String.valueOf(de.behindtheblocks.cinematic.client.obs.ObsBridge.port());
				}
				boolean on = de.behindtheblocks.cinematic.client.obs.ObsBridge.enabled();
				Flow f = new Flow(x, top, w);
				f.label("OBS").field(90, obsHost, v -> obsHost = v, "127.0.0.1", 60).field(40, obsPort, v -> obsPort = v, "4455", 6)
						.field(100, obsPassword, v -> obsPassword = v,
								de.behindtheblocks.cinematic.client.obs.ObsBridge.hasPassword() ? "Passwort (gespeichert)" : "Passwort", 80)
						.button(on ? "OBS: an" : "OBS: aus", "Ein-/ausschalten und speichern", () -> saveObs(!on))
						.button("Speichern & verbinden", null, () -> saveObs(true))
						.button("OBS testen", "Fragt OBS nach Version und Aufnahme-Status (Antwort erscheint in der OBS-Zeile)", () -> {
							de.behindtheblocks.cinematic.client.obs.ObsBridge.test();
							status = "OBS wird gefragt …";
							rebuildWidgets();
						});
				f.newline().label("Cue").field(100, cueLabel, v -> cueLabel = v, "Name (optional)", 60)
						.fill(56, 120, cueCommand, v -> cueCommand = v, "Befehl, z. B. btb scene restart opening", 250)
						.button("+ Cue", "An die Cue-Liste anhängen", () -> {
							if (!cueCommand.isBlank()) {
								DirectorHotkeys.addCue(cueLabel, cueCommand);
								cueCommand = "";
								cueLabel = "";
								status = "Cue hinzugefügt";
								rebuildWidgets();
							}
						});
				return f.end();
			}
			default -> {
				return top;
			}
		}
	}

	private void saveObs(boolean on) {
		int port;
		try {
			port = Integer.parseInt(obsPort.trim());
		} catch (NumberFormatException e) {
			status = "Port muss eine Zahl sein";
			rebuildWidgets();
			return;
		}
		de.behindtheblocks.cinematic.client.obs.ObsBridge.configure(on, obsHost, port, obsPassword);
		obsPassword = "";
		status = on ? "OBS gespeichert – verbinde…" : "OBS ausgeschaltet";
		rebuildWidgets();
	}

	private void cycleScene(List<String> ids, int d) {
		builderScene = cycle(ids, builderScene, d);
		rebuildWidgets();
	}

	private String cycle(List<String> ids, String current, int d) {
		if (ids.isEmpty()) {
			return current;
		}
		int i = ids.indexOf(current);
		return ids.get(Math.floorMod(i < 0 ? (d > 0 ? -1 : 0) + d : i + d, ids.size()));
	}

	private String time() {
		return seconds(builderTime, "0");
	}

	private static String seconds(String v, String fallback) {
		try {
			double d = Double.parseDouble(v.trim());
			return d < 0 ? fallback : fmt(d);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static String fmt(double d) {
		String s = String.format(java.util.Locale.ROOT, "%.2f", d);
		return s.replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private static String strip(String c) {
		c = c.trim();
		return c.startsWith("/") ? c.substring(1) : c;
	}

	/**
	 * One place for every click: run it, or – in click mode – add it to the scene being built or
	 * to the cue list. Management actions (meta) always run.
	 */
	private void perform(String command, String label, boolean meta) {
		boolean server = !command.startsWith("local:") && !command.startsWith("obs:");
		if (!meta && clickMode == ClickMode.SCENE && server) {
			cmd("btb scene add " + builderScene + " " + time() + " " + command);
			status = "→ Szene '" + builderScene + "' bei " + time() + " s: " + command;
			refreshSoon();
			return;
		}
		if (!meta && clickMode == ClickMode.ON_CLOSE && server) {
			PENDING.add(command);
			status = PENDING.size() + " Aktion(en) starten beim Schließen: " + (label != null ? label : command);
			rebuildWidgets();
			return;
		}
		if (!meta && clickMode == ClickMode.SLOT && (server || command.startsWith("obs:"))) {
			DirectorHotkeys.setSlot(targetSlot, label, command);
			status = "Numpad " + targetSlot + " = " + (label != null ? label : command);
			targetSlot = targetSlot % 9 + 1;
			rebuildWidgets();
			return;
		}
		if (!meta && clickMode == ClickMode.CUES && (server || command.startsWith("obs:"))) {
			DirectorHotkeys.addCue(label, command);
			status = "→ Cue " + DirectorHotkeys.cues().size() + ": " + (label != null ? label : command);
			rebuildWidgets();
			return;
		}
		cmd(command);
		if (command.startsWith("local:") || command.startsWith("obs:")) {
			rebuildWidgets();
		} else {
			refreshSoon();
		}
	}

	private record PlacedRow(Row row, int x, int y, int labelW) {
	}

	private List<PlacedRow> rows = new ArrayList<>();
	private String pageLabel;
	private int[] pageLabelPos;
	private int[] emptyHint;

	private Button button(Action a, int x, int y, int w) {
		return button(a, a.label, x, y, w);
	}

	private Button button(Action a, String fullLabel, int x, int y, int w) {
		int key = 0;
		for (int n = 1; n <= 9; n++) {
			if (a.command.equals(DirectorHotkeys.slot(n))) {
				key = n;
				break;
			}
		}
		Button.Builder b = Button.builder(Component.literal(key > 0 ? a.label + " §e" + key : a.label), btn -> perform(a.command, fullLabel, a.meta)).bounds(x, y, w, 18);
		if (a.tooltip != null) {
			b.tooltip(Tooltip.create(Component.literal(a.tooltip + "\n/" + a.command + (key > 0 ? "\nTaste: Numpad " + key : ""))));
		} else {
			b.tooltip(Tooltip.create(Component.literal("/" + a.command + (key > 0 ? "\nTaste: Numpad " + key : ""))));
		}
		return b.build();
	}

	private void cmd(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (command.startsWith("obs:")) {
			de.behindtheblocks.cinematic.client.obs.ObsBridge.command(command.substring(4));
			status = "OBS " + command.substring(4);
			return;
		}
		if (command.equals("local:cleanfeed")) {
			DirectorHotkeys.toggleCleanFeed();
			status = "Clean Feed " + (DirectorHotkeys.cleanFeed() ? "an" : "aus");
			return;
		}
		if (command.equals("local:newscene") || command.startsWith("local:edit:")) {
			builderScene = command.startsWith("local:edit:") ? command.substring(11) : "neue_szene";
			currentTab = Tab.BUILDER;
			page = 0;
			showForm = true;
			status = command.startsWith("local:edit:") ? "Bearbeite „" + builderScene + "“" : "Namen eintippen und „Neu“ klicken";
			return;
		}
		if (command.startsWith("local:time:")) {
			builderTime = command.substring(11);
			status = "Zeit: " + builderTime + " s";
			return;
		}
		if (command.startsWith("local:slot")) {
			String[] p = command.substring(6).split(":");
			int n = Integer.parseInt(p[1]);
			switch (p[0]) {
				case "slotfire" -> DirectorHotkeys.fireSlot(n);
				case "slotclear" -> DirectorHotkeys.setSlot(n, null, "");
				case "slottarget" -> {
					targetSlot = n;
					clickMode = ClickMode.SLOT;
					status = "Jetzt die gewünschte Aktion anklicken – sie kommt auf Numpad " + n;
					return;
				}
				default -> {
				}
			}
			status = "Numpad " + n + ": " + (DirectorHotkeys.slotLabel(n).isEmpty() ? "frei" : DirectorHotkeys.slotLabel(n));
			return;
		}
		if (command.startsWith("local:cue")) {
			String[] p = command.substring(6).split(":");
			int i = p.length > 1 ? Integer.parseInt(p[1]) : 0;
			switch (p[0]) {
				case "cueup" -> DirectorHotkeys.moveCue(i, -1);
				case "cuedown" -> DirectorHotkeys.moveCue(i, 1);
				case "cuedel" -> DirectorHotkeys.removeCue(i);
				case "cuepointer" -> DirectorHotkeys.setPointer(i);
				case "cueclear" -> DirectorHotkeys.clearCues();
				case "cues" -> {
					DirectorHotkeys.load();
					de.behindtheblocks.cinematic.client.obs.ObsBridge.load();
				}
				default -> {
				}
			}
			status = "Cue-Liste: " + DirectorHotkeys.cues().size() + " Cue(s)";
			return;
		}
		if (command.equals("local:cues")) {
			DirectorHotkeys.load();
			de.behindtheblocks.cinematic.client.obs.ObsBridge.load();
			status = "Cue-Liste & OBS-Konfiguration neu geladen";
			return;
		}
		if (mc.player != null) {
			DirectorHotkeys.muteFeedback(); // the status line shows it; chat stays clean (errors still come through)
			mc.player.connection.sendCommand(command);
			status = "/" + command;
		}
	}

	/** Ask for fresh state shortly after an action so states (running, portal stage) update. */
	private void refreshSoon() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) {
			mc.player.connection.sendCommand("btb director refresh");
		}
	}

	private List<JsonObject> objects(String key) {
		List<JsonObject> out = new ArrayList<>();
		if (state != null && state.has(key)) {
			for (JsonElement e : state.getAsJsonArray(key)) {
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	private List<String> strings(String key) {
		List<String> out = new ArrayList<>();
		if (state != null && state.has(key)) {
			JsonArray a = state.getAsJsonArray(key);
			a.forEach(e -> out.add(e.getAsString()));
		}
		return out;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// Deliberately no world blur/darkening: the director must see the shot.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (!de.behindtheblocks.cinematic.client.obs.ObsBridge.uiMayShow()) {
			return; // OBS is still pausing: draw nothing yet so not a single frame of menu is recorded
		}
		int panelX = 10, panelY = 10, panelW = width - 20, panelH = height - 20;
		int tabW = Math.min(110, panelW / 5);
		g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
		g.fill(panelX, panelY, panelX + panelW, panelY + 2, ACCENT);
		g.fill(panelX + tabW + 2, panelY + 30, panelX + tabW + 3, panelY + panelH - 32, 0x40FFFFFF);
		g.text(font, Component.literal("BEHIND THE BLOCKS — DIRECTOR").withStyle(TITLE_FONT), panelX + 10, panelY + 10, 0xFFFFE0C0, true);
		long running = objects("scenes").stream().filter(s -> !s.get("state").getAsString().equals("stopped")).count();
		String info = running + " Szene(n) aktiv";
		int titleEnd = panelX + 10 + font.width(Component.literal("BEHIND THE BLOCKS — DIRECTOR").withStyle(TITLE_FONT)) + 10;
		int infoX = panelX + panelW - font.width(info) - 124;
		if (infoX > titleEnd) { // only where there is room next to the title
			g.text(font, info, infoX, panelY + 12, running > 0 ? 0xFF7CFF7C : 0xFF999999, false);
		}
		for (int i = 0; i < fieldLabels.size(); i++) {
			g.text(font, Component.literal(fieldLabelTexts.get(i)), fieldLabels.get(i)[0], fieldLabels.get(i)[1], 0xFFBBBBBB, false);
		}
		if (clickMode == ClickMode.ON_CLOSE && !PENDING.isEmpty()) {
			g.text(font, "● " + PENDING.size() + " Aktion(en) starten beim Schließen", panelX + 10, panelY + 24, 0xFFFFC060, false);
		} else if (clickMode != ClickMode.RUN && clickMode != ClickMode.ON_CLOSE) {
			String m = switch (clickMode) {
				case SCENE -> "● Klicks → Szene „" + builderScene + "“ bei " + builderTime + " s";
				case SLOT -> "● Nächster Klick → Taste Numpad " + targetSlot;
				default -> "● Klicks → Cue-Liste";
			};
			g.text(font, m, panelX + 10, panelY + 24, 0xFFFF6060, false);
		}
		for (PlacedRow r : rows) {
			g.fill(r.x - 2, r.y, panelX + panelW - 8, r.y + ROW_H - 2, 0x22FFFFFF);
			String l = r.row.label;
			if (font.width(l) > r.labelW) { // never under the buttons
				l = font.plainSubstrByWidth(l, Math.max(10, r.labelW - font.width("…"))) + "…";
			}
			g.text(font, l, r.x + 4, r.y + 6, r.row.color, false);
		}
		if (pageLabel != null) {
			g.text(font, pageLabel, pageLabelPos[0], pageLabelPos[1], 0xFFAAAAAA, false);
		}
		if (emptyHint != null) {
			g.text(font, currentTab == Tab.BUILDER ? "Noch keine Schritte – oben hinzufügen oder live aufnehmen" : "Nichts angelegt", emptyHint[0], emptyHint[1], 0xFF888888, false);
		}
		if (!status.isEmpty()) {
			int room = panelW - 240 - 96;
			if (room > 30) {
				String s = font.width(status) > room ? font.plainSubstrByWidth(status, room - font.width("…")) + "…" : status;
				g.text(font, s, panelX + 240, panelY + panelH - 21, 0xFF888888, false);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private String shownObsStatus;

	/** Live OBS status: rebuild the Aufnahme tab when OBS answers (connect, test, pause …). */
	@Override
	public void tick() {
		super.tick();
		String now = de.behindtheblocks.cinematic.client.obs.ObsBridge.status();
		if (currentTab == Tab.RECORDING && shownObsStatus != null && !now.equals(shownObsStatus)) {
			rebuildWidgets();
		}
		shownObsStatus = now;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
