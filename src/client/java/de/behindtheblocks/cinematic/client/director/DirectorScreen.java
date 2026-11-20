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

import de.behindtheblocks.cinematic.client.i18n.ClientI18n;

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

	/** Main tabs: sorted by task, not by technology. */
	private enum Tab {
		START("Start"), SCENES("Szenen"), CAMERA("Kamera"), PICTURE("Bild & Ton"), WORLD("Welt"), REPLAY("Replay"),
		CREDITS("Credits"), SETTINGS("Einstellungen");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	/** The pages inside a tab (small buttons under the title). */
	private enum Section {
		START(Tab.START, "Übersicht"), WIZARD(Tab.START, "Assistenten"), TEMPLATES(Tab.START, "Vorlagen"), INFO(Tab.START, "Info"),
		SCENES(Tab.SCENES, "Szenen"), BUILDER(Tab.SCENES, "Baukasten"), STORYBOARD(Tab.SCENES, "Storyboard"), DIALOG(Tab.SCENES, "Dialog"),
		CAMERA(Tab.CAMERA, "Kamera"), PATHS(Tab.CAMERA, "Kamerapfade"),
		EFFECTS(Tab.PICTURE, "Effekte"), HUD(Tab.PICTURE, "Texte"), SOUND(Tab.PICTURE, "Ton"),
		ACTION(Tab.WORLD, "Action"), CROWD(Tab.WORLD, "Gruppen"), WEATHER(Tab.WORLD, "Zeit & Wetter"), PORTALS(Tab.WORLD, "Portale"),
		DESTRUCTION(Tab.WORLD, "Zerstörung"), SHOWCASE(Tab.WORLD, "Showcase"),
		REPLAY(Tab.REPLAY, "Replays & Export"),
		CREDITS(Tab.CREDITS, "Credits"),
		PLAYERS(Tab.SETTINGS, "Crew & Rollen"), RECORDING(Tab.SETTINGS, "OBS & Tasten"), PROJECT(Tab.SETTINGS, "Projekt & Versionen");

		final Tab tab;
		final String label;

		Section(Tab tab, String label) {
			this.tab = tab;
			this.label = label;
		}

		static List<Section> of(Tab t) {
			List<Section> l = new ArrayList<>();
			for (Section s : values()) {
				if (s.tab == t) {
					l.add(s);
				}
			}
			return l;
		}
	}

	private static Tab currentTab = Tab.START;
	private static Section section = Section.START;
	private static final java.util.Map<Tab, Section> LAST_SECTION = new java.util.EnumMap<>(Tab.class);

	/** Where a click on an action goes: run it, or add it to the scene / cue list being built. */
	private enum ClickMode {
		RUN("Klick: sofort"), ON_CLOSE("🎬 Dreh-Modus"), SCENE("Klick → Szene"), CUES("Klick → Cue-Liste"), SLOT("Klick → Taste");

		final String label;

		ClickMode(String label) {
			this.label = label;
		}
	}

	// Form values survive refreshes of the screen (state updates rebuild all widgets).
	private static ClickMode clickMode = ClickMode.RUN;
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
	/** Entry number loaded into the form for editing (0 = none). */
	private static int creditEditNr;
	private static String creditName = "";
	private static String creditIcon = "";
	private static String creditLink = "";
	private static String creditsNewId = "";
	private static String cueLabel = "";
	private static String cueCommand = "";
	private static String replayName = "take";
	private static String replayDelete; // first ✕ click arms, the second deletes
	private static String cameraDelete;
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
		super(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("Director"));
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
		buttonCommands.clear();
		int panelX = 10, panelY = 10, panelW = width - 20, panelH = height - 20;
		int tabW = tabWidth(panelW);
		int y = panelY + 38;
		boolean all = StudioClient.allTabs();
		// Tab height adapts to the window so all tabs fit even at large GUI scales.
		int step = Math.max(all ? 10 : 12, Math.min(22, (panelH - 38 - 34) / (all ? Section.values().length : Tab.values().length)));
		int tabH = Math.max(all ? 9 : 11, step - 2);
		if (all) {
			// every page its own tab (the old, long list) – scrolls when the window is too low for all of them
			Section[] secAll = Section.values();
			int areaTop = y, areaBottom = panelY + panelH - 32;
			step = 15;
			tabH = 13;
			int fit = (areaBottom - areaTop) / step;
			boolean scroll = secAll.length > fit;
			if (scroll) {
				fit = (areaBottom - areaTop - 2 * step) / step;
				tabScroll = Math.max(0, Math.min(tabScroll, secAll.length - fit));
				if (tabScroll == 0 && section.ordinal() >= fit) {
					tabScroll = Math.min(section.ordinal() - fit + 1, secAll.length - fit);
				}
				addRenderableWidget(Button.builder(ClientI18n.c("▲"), btn -> {
					tabScroll = Math.max(0, tabScroll - 3);
					rebuildWidgets();
				}).bounds(panelX + 8, y, tabW - 8, tabH).build());
				y += step;
			} else {
				tabScroll = 0;
			}
			tabListArea = new int[] {panelX + 8, areaTop, panelX + tabW, areaBottom};
			for (int k = tabScroll; k < Math.min(secAll.length, tabScroll + fit); k++) {
				Section sct = secAll[k];
				addRenderableWidget(Button.builder(ClientI18n.c((sct == section ? "▶ " : "") + sct.label), btn -> {
					section = sct;
					currentTab = sct.tab;
					LAST_SECTION.put(currentTab, sct);
					search = "";
					page = 0;
					rebuildWidgets();
				}).bounds(panelX + 8, y, tabW - 8, tabH).build());
				y += step;
			}
			if (scroll) {
				int maxScroll = secAll.length - fit;
				addRenderableWidget(Button.builder(ClientI18n.c("▼"), btn -> {
					tabScroll = Math.min(maxScroll, tabScroll + 3);
					rebuildWidgets();
				}).bounds(panelX + 8, y, tabW - 8, tabH).build());
			}
		} else {
			tabListArea = null;
		}
		for (Tab t : all ? new Tab[0] : Tab.values()) {
			Button b = Button.builder(ClientI18n.c((t == currentTab ? "▶ " : "") + t.label), btn -> {
				LAST_SECTION.put(currentTab, section);
				currentTab = t;
				section = LAST_SECTION.getOrDefault(t, Section.of(t).get(0));
				search = "";
				page = 0;
				rebuildWidgets();
			}).bounds(panelX + 8, y, tabW - 8, tabH).build();
			addRenderableWidget(b);
			y += step;
		}
		// title row, right side: search · stop everything · click mode
		int rx = panelX + panelW - 8;
		int modeW = Math.max(84, font.width(ClientI18n.t(clickMode.label)) + 14);
		rx -= modeW;
		addRenderableWidget(Button.builder(ClientI18n.c(clickMode.label), b -> {
			clickMode = ClickMode.values()[(clickMode.ordinal() + 1) % ClickMode.values().length];
			status = switch (clickMode) {
				case ON_CLOSE -> "Dreh-Modus: Klicks werden gesammelt und starten, sobald das Menü zu ist (nichts vom Menü im Bild)";
				case RUN -> "Klicks führen Aktionen sofort aus";
				case SCENE -> "Klicks fügen zur Szene '" + builderScene + "' bei " + builderTime + " s hinzu (Baukasten)";
				case CUES -> "Klicks hängen Cues an die Cue-Liste an (Einstellungen → OBS & Tasten)";
				case SLOT -> "Nächster Klick wird auf Numpad " + targetSlot + " gelegt";
			};
			rebuildWidgets();
		}).tooltip(Tooltip.create(ClientI18n.c("Was ein Klick tut: sofort ausführen · Dreh-Modus (erst beim Schließen) · zur Szene / Cue-Liste / auf eine Taste")))
				.bounds(rx, panelY + 6, modeW, 16).build());
		if (clickMode == ClickMode.SLOT) {
			rx -= 52;
			addRenderableWidget(Button.builder(ClientI18n.c("Num " + targetSlot + " ▸"), b -> {
				targetSlot = targetSlot % 9 + 1;
				rebuildWidgets();
			}).tooltip(Tooltip.create(ClientI18n.c("Auf welche Taste der nächste Klick gelegt wird"))).bounds(rx, panelY + 6, 48, 16).build());
		}
		rx -= 20;
		addRenderableWidget(Button.builder(ClientI18n.c("ⓘ"), b -> {
			section = Section.INFO;
			currentTab = Tab.START;
			search = "";
			page = 0;
			rebuildWidgets();
		}).tooltip(Tooltip.create(ClientI18n.c("Info & Credits"))).bounds(rx, panelY + 6, 16, 16).build());
		int stopW = font.width(ClientI18n.t("■ Alles stoppen")) + 14;
		rx -= stopW + 4;
		addRenderableWidget(Button.builder(ClientI18n.c("■ Alles stoppen"), b -> perform("btb stop", "■ Alles stoppen", true))
				.tooltip(Tooltip.create(ClientI18n.c("Stoppt Szenen, Kamera, Effekte, Zeitlupe, Ton und Dialog sofort – das Set bleibt, wie es ist")))
				.bounds(rx, panelY + 6, stopW, 16).build());
		// the search field wins over the long title (it then becomes "BTB DIRECTOR")
		int searchW = Math.min(120, rx - (panelX + 10) - 110);
		if (searchW >= 50) {
			rx -= searchW + 6;
			EditBox box = new EditBox(font, rx, panelY + 6, searchW, 16, ClientI18n.c("Suche"));
			box.setMaxLength(40);
			box.setValue(search);
			box.setHint(ClientI18n.c("🔍 Suchen …").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			box.setResponder(v -> {
				if (!v.equals(search)) {
					search = v;
					page = 0;
					searchFocus = true;
					rebuildWidgets();
				}
			});
			addRenderableWidget(box);
			if (searchFocus) {
				setFocused(box);
				box.setFocused(true);
				box.moveCursorToEnd(false);
			}
			titleRoom = rx - (panelX + 10) - 8;
		} else {
			titleRoom = rx - (panelX + 10) - 8;
		}
		int contentX = panelX + tabW + 10, contentW = panelW - tabW - 20;
		int contentTop = panelY + 38, contentBottom = panelY + panelH - 32;
		buildContent(contentX, contentTop, contentW, contentBottom);

		int by = panelY + panelH - 26;
		int bx = panelX + 8;
		// narrow windows: short labels, so nothing hides behind "Schließen"
		boolean narrow = font.width(ClientI18n.t("↻⟲ Set zurück↶ RückgängigTabs: kompaktAnsicht: EinsteigerSchließen")) + 6 * 18 + 90 > panelW;
		bx += bottomButton("↻", "Aktualisieren", bx, by, () -> cmd("btb director refresh"));
		bx += bottomButton(narrow ? "⟲ Set" : "⟲ Set zurück", "Alle Szenen zurücksetzen, Portale schließen, Zerstörung wiederherstellen, Effekte löschen", bx, by, () -> {
			cmd("btb resetall");
			refreshSoon();
		});
		JsonObject studio = studio();
		int undo = studio.has("undo") ? studio.get("undo").getAsInt() : 0;
		String next = studio.has("undo_next") ? studio.get("undo_next").getAsString() : "";
		bx += bottomButton(narrow ? "↶" : "↶ Rückgängig", undo == 0 ? "Nichts zum Rückgängigmachen" : "Letzte Änderung zurücknehmen (Strg+Z): " + next, bx, by, () -> perform("btb undo", "↶", true));
		bx += bottomButton(StudioClient.allTabs() ? "Tabs: alle" : "Tabs: kompakt",
				"Kompakt: 8 Tabs mit Unterseiten · Alle: jeder Bereich als eigener Tab links (wie früher)", bx, by, () -> {
					StudioClient.setAllTabs(!StudioClient.allTabs());
					status = StudioClient.allTabs() ? "Alle Bereiche als eigene Tabs" : "Kompakt: 8 Tabs mit Unterseiten";
					rebuildWidgets();
				});
		bx += bottomButton(narrow ? (StudioClient.pro() ? "Profi" : "Einsteiger") : StudioClient.pro() ? "Ansicht: Profi" : "Ansicht: Einsteiger",
				"Einsteiger: nur die wichtigsten Knöpfe · Profi: alle Feinheiten", bx, by, () -> {
					StudioClient.setPro(!StudioClient.pro());
					status = StudioClient.pro() ? "Profi-Ansicht: alle Knöpfe" : "Einsteiger-Ansicht: nur das Wichtigste";
					rebuildWidgets();
				});
		statusX = bx + 6;
		addRenderableWidget(Button.builder(ClientI18n.c("Schließen"), b -> onClose())
				.bounds(panelX + panelW - 88, by, 80, 18).build());
	}

	/** Left column: as wide as the longest tab name (never cut off), at most a third of the panel. */
	private int tabWidth(int panelW) {
		int need = 0;
		if (StudioClient.allTabs()) {
			for (Section s : Section.values()) {
				need = Math.max(need, font.width(ClientI18n.t("▶ " + s.label)) + 20);
			}
		} else {
			for (Tab t : Tab.values()) {
				need = Math.max(need, font.width(ClientI18n.t("▶ " + t.label)) + 20);
			}
		}
		return Math.max(Math.min(110, panelW / 5), Math.min(need, panelW / 3));
	}

	private int bottomButton(String label, String tooltip, int x, int y, Runnable action) {
		int w = font.width(ClientI18n.t(label)) + 14;
		addRenderableWidget(Button.builder(ClientI18n.c(label), b -> action.run()).tooltip(Tooltip.create(ClientI18n.c(tooltip))).bounds(x, y, w, 18).build());
		return w + 4;
	}

	private JsonObject studio() {
		return state != null && state.has("studio") ? state.getAsJsonObject("studio") : new JsonObject();
	}

	@Override
	public void removed() {
		if (open == this) {
			open = null;
			if (clickMode != ClickMode.ON_CLOSE) {
				clickMode = ClickMode.RUN; // never leave a "clicks add instead of run" mode armed (the shooting mode stays)
			}
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
	private record Row(String label, int color, List<Action> actions, boolean pro) {
		Row {
			label = de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(label);
		}

		Row(String label, int color, List<Action> actions) {
			this(label, color, actions, false);
		}

		/** Only shown in the pro view. */
		Row asPro() {
			return new Row(label, color, actions, true);
		}
	}

	private record Action(String label, String command, String tooltip, boolean meta) {
		Action {
			label = de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(label);
			tooltip = tooltip == null ? null : de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(tooltip);
		}

		Action(String label, String command, String tooltip) {
			this(label, command, tooltip, false);
		}
	}

	private void buildContent(int x, int top, int w, int bottom) {
		List<Row> rows = new ArrayList<>();
		List<Action> header = new ArrayList<>();
		fieldLabels.clear();
		fieldLabelTexts.clear();
		if (section.tab != currentTab) {
			section = Section.of(currentTab).get(0);
		}
		// pages of this tab
		List<Section> secs = StudioClient.allTabs() ? List.of(section) : Section.of(currentTab);
		int sx = x;
		boolean form = hasForm(section) && search.isBlank();
		int formBtnW = form ? font.width(ClientI18n.t(showForm && !formAuto ? "✎ Eingabe ▴" : "✎ Eingabe ▾")) + 12 : 0;
		if (secs.size() > 1 && search.isBlank()) {
			for (Section s : secs) {
				String l = (s == section ? "§n" : "") + s.label;
				int bw = font.width(ClientI18n.t(s.label)) + 12;
				if (sx > x && sx + bw > x + w - formBtnW - 4) {
					sx = x;
					top += 16;
				}
				addRenderableWidget(Button.builder(ClientI18n.c(l), b -> {
					section = s;
					LAST_SECTION.put(currentTab, s);
					page = 0;
					rebuildWidgets();
				}).bounds(sx, top, bw, 14).build());
				sx += bw + 3;
			}
		}
		if (form) {
			addRenderableWidget(Button.builder(ClientI18n.c(showForm && !formAuto ? "✎ Eingabe ▴" : "✎ Eingabe ▾"), b -> {
				if (formAuto) {
					formAuto = false;
					formPinned = true;
					showForm = true;
				} else {
					showForm = !showForm;
					formPinned = showForm;
				}
				rebuildWidgets();
			}).tooltip(Tooltip.create(ClientI18n.c(formAuto ? "Eingabefelder wurden für mehr Platz eingeklappt – klicken zum Ausklappen"
					: "Eingabefelder ein-/ausblenden (mehr Platz für die Liste)"))).bounds(x + w - formBtnW, top, formBtnW, 14).build());
		}
		if ((secs.size() > 1 && search.isBlank()) || form) {
			top += 18;
		}
		if (!search.isBlank()) {
			fieldLabels.add(new int[] {x, top + 2});
			fieldLabelTexts.add("§7Suche: „" + search + "“ – alle Bereiche");
			top += 14;
		}
		collect(rows, header);
		formAuto = false;
		if (form && showForm) {
			int before = children().size(), labels = fieldLabels.size();
			int newTop = buildForm(x, top, w);
			int listRows = (bottom - newTop - 22) / ROW_H;
			if (!formPinned && listRows < 3 && rows.size() > listRows && newTop > top) {
				// not enough room for the list: fold the form away (one click brings it back)
				List<net.minecraft.client.gui.components.events.GuiEventListener> added = new ArrayList<>(children().subList(before, children().size()));
				added.forEach(this::removeWidget);
				while (fieldLabels.size() > labels) {
					fieldLabels.remove(fieldLabels.size() - 1);
					fieldLabelTexts.remove(fieldLabelTexts.size() - 1);
				}
				formAuto = true;
				// relabel the toggle (it was built before we knew)
				rebuildSoon = true;
			} else {
				top = newTop;
			}
		}
		top = extraTop(x, top, w);
		layout(x, top, w, bottom, rows, header);
	}

	/** Rows (and header buttons) of the current page – or, while searching, of all pages. */
	private void collect(List<Row> rows, List<Action> header) {
		if (!search.isBlank()) {
			String q = search.toLowerCase(java.util.Locale.ROOT).trim();
			for (Section s : Section.values()) {
				if (s == Section.WIZARD) {
					continue;
				}
				List<Row> r = new ArrayList<>();
				List<Action> h = new ArrayList<>();
				content(s, r, h);
				String where = s.tab.label + " › " + s.label;
				for (Action a : h) {
					if (matches(a, q)) {
						rows.add(new Row(where + ": " + a.label, 0xFFFFE0C0, List.of(a)));
					}
				}
				for (Row row : r) {
					if (row.label.toLowerCase(java.util.Locale.ROOT).contains(q)) {
						rows.add(new Row(where + ": " + row.label, row.color, row.actions));
					} else {
						List<Action> m = row.actions.stream().filter(a -> matches(a, q)).toList();
						if (!m.isEmpty()) {
							rows.add(new Row(where + ": " + row.label, row.color, m));
						}
					}
				}
			}
			if (rows.isEmpty()) {
				rows.add(new Row("Nichts gefunden für „" + search + "“", 0xFF888888, List.of(new Action("Suche leeren", "local:searchclear", null, true))));
			}
			return;
		}
		content(section, rows, header);
		if (!StudioClient.pro()) {
			rows.removeIf(Row::pro);
		}
		if (rows.isEmpty() && header.isEmpty() || rows.isEmpty() && emptyHelp(section) != null && !hasForm(section)) {
			Row help = emptyHelp(section);
			if (help != null) {
				rows.add(help);
			}
		} else if (rows.isEmpty() && emptyHelp(section) != null) {
			rows.add(emptyHelp(section));
		}
	}

	private static boolean matches(Action a, String q) {
		return a.label.toLowerCase(java.util.Locale.ROOT).contains(q) || a.command.toLowerCase(java.util.Locale.ROOT).contains(q)
				|| a.tooltip != null && a.tooltip.toLowerCase(java.util.Locale.ROOT).contains(q);
	}

	private void content(Section sec, List<Row> rows, List<Action> header) {
		switch (sec) {
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
							new Action("↺ Reset", "btb scene reset " + id, "Szene zurücksetzen (Startzustand)"),
							new Action("✎ Bearbeiten", "local:edit:" + id, "Im Baukasten bearbeiten (Schritte, Zeiten, auf Taste legen)", true))));
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
								new Action("⏱ Zeit", "local:time:" + t, "Zeitfeld auf " + t + " s setzen", true),
								new Action("✕", "btb scene remove " + builderScene + " " + nr, "Schritt löschen", true))));
					}
				}
			}
			case CAMERA -> {
				header.add(new Action("Kamera stop", "btb camera stop", "Zurück zum eigenen Blick (Fahrt, Orbit, Verfolger, Ego …)"));
				header.add(new Action("Stop alle", "btb camera stop @a", "Kamera bei allen Spielern beenden"));
				header.add(new Action("1. Person", "btb camera perspective first", null));
				header.add(new Action("3. Person", "btb camera perspective third", null));
				header.add(new Action("Zoom 30°", "btb camera zoom 30 1.5", null));
				header.add(new Action("Zoom ⟲", "btb camera zoom reset", null));
				header.add(new Action("● Pfad aufnehmen", "btb camera record take_" + (System.currentTimeMillis() / 1000 % 100000), "Fahrt fliegen, dann 'Aufnahme stop'"));
				header.add(new Action("■ Aufnahme stop", "btb camera record stop", null));
				rows.add(new Row("Orbit (nächster Darsteller)", 0xFFFFFFFF, List.of(
						new Action("Nah 10 s", "btb camera orbit entity @p[tag=!kamera,distance=0.1..] 4 1.5 10", "Kreist in 4 Blöcken Abstand um ihn"),
						new Action("Weit 16 s", "btb camera orbit entity @p[tag=!kamera,distance=0.1..] 9 4 16", null),
						new Action("Um mich 12 s", "execute at @s run btb camera orbit ~ ~ ~ 8 3 12", "Kreist um die Stelle, an der du stehst"))));
				rows.add(new Row("Verfolger (nächster Darsteller)", 0xFFFFFFFF, List.of(
						new Action("Seite", "btb camera follow @p[tag=!kamera,distance=0.1..] side", "Seitenansicht, weich nachgeführt"),
						new Action("Hinten", "btb camera follow @p[tag=!kamera,distance=0.1..] behind", null),
						new Action("Oben", "btb camera follow @p[tag=!kamera,distance=0.1..] top", null),
						new Action("Fest", "btb camera follow @p[tag=!kamera,distance=0.1..] fixed", "Kamera bleibt stehen und schaut ihm nach"),
						new Action("Auto-Regie", "btb camera auto @p[tag=!kamera,distance=0.1..] 5", "Wechselt alle 5 s die Perspektive (harte Schnitte)"))));
				rows.add(new Row("Handkamera", 0xFFFFFFFF, List.of(new Action("Ruhig", "btb camera handheld 0.5", "leichtes Atmen der Kamera"),
						new Action("Doku", "btb camera handheld 1", null), new Action("Lauf", "btb camera handheld 1.8", null),
						new Action("Aus", "btb camera handheld off", null))));
				rows.add(new Row("Dolly-Zoom (Vertigo) auf den Block vor dir", 0xFFFFFFFF, List.of(
						new Action("Ran", "execute anchored eyes positioned ^ ^ ^6 run btb camera dollyzoom ~ ~ ~ 14 4 4", "Kamera fährt ran, Hintergrund weitet sich"),
						new Action("Weg", "execute anchored eyes positioned ^ ^ ^6 run btb camera dollyzoom ~ ~ ~ 4 14 4", "Kamera fährt weg, Hintergrund rückt heran"))).asPro());
				rows.add(new Row("Bild-im-Bild: Sicht des nächsten Darstellers", 0xFFFFFFFF, List.of(
						new Action("Oben rechts", "btb pip @p[tag=!kamera,tag=!pip,distance=0.1..] tr 0.3", "zweites Fenster mit Tag pip + OBS-Quelle „BTB PiP“"),
						new Action("Unten links", "btb pip @p[tag=!kamera,tag=!pip,distance=0.1..] bl 0.3", null),
						new Action("Aus", "btb pip off", null))).asPro());
				rows.add(new Row("Ego-Kamera (Bodycam des nächsten Darstellers)", 0xFFFFFFFF, List.of(
						new Action("An", "btb camera ego @p[tag=!kamera,distance=0.1..]", "Kopf wippt beim Gehen, dreht etwas nach, atmet"),
						new Action("Aus", "btb camera stop", null))).asPro());
				rows.add(new Row("Sicht-Schnitt (Augen des Darstellers)", 0xFFFFFFFF, List.of(
						new Action("3 s", "btb camera pov @p[tag=!kamera,distance=0.1..] 3", "Aufnahme-Kamera schaut kurz durch seine Augen"),
						new Action("8 s", "btb camera pov @p[tag=!kamera,distance=0.1..] 8", null))).asPro());
				rows.add(new Row("Zeitlupe", 0xFFFFFFFF, List.of(new Action("½ 3 s", "btb slowmo 0.5 3", null),
						new Action("¼ 3 s", "btb slowmo 0.25 3", null), new Action("Aus", "btb slowmo off", null))));
				header.add(new Action("Kamerapfade →", "local:section:PATHS", "Gespeicherte Kamerafahrten abspielen, ansehen und bearbeiten", true));
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
						new Action("Aus", "btb effects tint off 2", null))).asPro());
				rows.add(new Row("Tiefenschärfe", 0xFFFFFFFF, List.of(new Action("Auto", "btb effects dof auto 0.6 1", "Schärfe folgt Darsteller / Blickpunkt"),
						new Action("Nah 3", "btb effects dof 3 0.7 1", null), new Action("Fern 20", "btb effects dof 20 0.6 1", null),
						new Action("Aus", "btb effects dof off 1", null))));
				rows.add(new Row("Anamorph-Streifen", 0xFFFFFFFF, List.of(new Action("An", "btb effects anamorph 0.7 1", "lange Lichtstreifen an hellen Lichtern"),
						new Action("Aus", "btb effects anamorph off 1", null))).asPro());
				rows.add(new Row("Rückblende", 0xFFFFFFFF, List.of(new Action("An", "btb effects flashback 0.7 1.5", null),
						new Action("Aus", "btb effects flashback off 1.5", null))).asPro());
				rows.add(new Row("Bildformat", 0xFFFFFFFF, List.of(new Action("2.39", "btb effects format 2.39 1", null),
						new Action("1.85", "btb effects format 1.85 1", null), new Action("4:3", "btb effects format 4x3 1", null),
						new Action("Aus", "btb effects format off 1", null))));
				rows.add(new Row("Farbkorrektur (LUT)", 0xFFFFFFFF, List.of(new Action("Nether", "btb effects lut nether 0.8 2", null),
						new Action("Film", "btb effects lut film 0.8 2", null), new Action("Kalt", "btb effects lut kalt 0.8 2", null),
						new Action("Bleach", "btb effects lut bleach 0.8 2", null), new Action("Aus", "btb effects lut off 2", null))).asPro());
				rows.add(new Row("Fokus-Zug", 0xFFFFFFFF, List.of(new Action("Scharf ziehen", "btb effects focus 2.5 6", "unscharf → scharf"),
						new Action("Raus", "btb effects focus 2 6 out", "scharf → unscharf"), new Action("Aus", "btb effects blur off 1", null))).asPro());
				rows.add(new Row("Filmkorn", 0xFFFFFFFF, List.of(new Action("Leicht", "btb effects grain 0.3 2", null),
						new Action("Mittel", "btb effects grain 0.6 2", null), new Action("Stark", "btb effects grain 1 2", null),
						new Action("Aus", "btb effects grain off 2", null))).asPro());
				rows.add(new Row("Hitzeflimmern", 0xFFFFFFFF, List.of(new Action("Leicht", "btb effects haze 0.3 2", null),
						new Action("Stark", "btb effects haze 0.9 2", null), new Action("Aus", "btb effects haze off 2", null))).asPro());
				rows.add(new Row("Ton dumpf (nach Explosion)", 0xFFFFFFFF, List.of(new Action("Dumpf 6 s", "btb effects muffle 6 0.85", null),
						new Action("+ Pfeifen", "btb sound play ear_ring 0.7 1", null), new Action("Stille", "btb sound volume 0.1", "Kino-Ton leiser vor dem großen Moment"),
						new Action("Ton zurück", "btb sound volume 1", null))).asPro());
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
						String base = "btb hud credits " + creditsId;
						rows.add(new Row((i + 1) + ". " + en.get(i).getAsString(), creditEditNr == i + 1 ? 0xFF80FF80 : 0xFFFFE0C0, List.of(
								new Action("▲", base + " move " + (i + 1) + " up", "nach oben", true),
								new Action("▼", base + " move " + (i + 1) + " down", "nach unten", true),
								new Action("✎", "local:creditedit:" + (i + 1), "In die Felder oben laden, ändern, „Übernehmen“", true),
								new Action("✕", base + " remove " + (i + 1), "Eintrag löschen", true))));
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
							new Action("Enthüllen", "btb showcase play " + id + " reveal", null),
							new Action("Puls", "btb showcase play " + id + " pulse", null),
							new Action("Explodieren", "btb showcase play " + id + " explode", null),
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
							new Action("↺ Zurück", "btb destruction restore " + id, "Aus dem Backup wiederherstellen"))));
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
				studioContent(sec, rows, header); // roles on top
			}
			default -> studioContent(sec, rows, header);
		}
	}

	private void layout(int x, int top, int w, int bottom, List<Row> rows, List<Action> header) {
		// Header actions.
		int hx = x, hy = top;
		for (Action a : header) {
			int bw = font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(a.label)) + 14;
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
				int bw = Math.max(20, font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(act.label)) + 12) + 3;
				int room = w - (first ? Math.min(minLabel, font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(r.label)) + 12) : 8);
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
				int bw = Math.max(20, font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(a.label)) + 12);
				bx -= bw;
				addRenderableWidget(button(a, r.label.isBlank() ? a.label : r.label.trim() + ": " + a.label, bx, y + 1, bw));
				bx -= 3;
			}
			this.rows.add(new PlacedRow(r, x, y, bx - x - 8));
			y += ROW_H;
		}
		if (pages > 1) {
			addRenderableWidget(Button.builder(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("◀"), b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			}).bounds(x, bottom - 20, 20, 18).build());
			addRenderableWidget(Button.builder(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("▶"), b -> {
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
		EditBox box = new EditBox(font, x, y, w, 16, de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(hint));
		box.setMaxLength(max);
		box.setValue(value == null ? "" : value);
		// The vanilla hint is not clipped: shorten it to the box.
		String h = font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(hint)) > w - 8 ? font.plainSubstrByWidth(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(hint), Math.max(4, w - 8 - font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t("…")))) + "…" : hint;
		box.setHint(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(h).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		box.setResponder(setter);
		addRenderableWidget(box);
		return box;
	}

	private void label(int x, int y, String text) {
		fieldLabels.add(new int[] {x, y + 4});
		fieldLabelTexts.add(text);
	}

	private Button small(String text, int x, int y, int w, String tooltip, Runnable action) {
		Button.Builder b = Button.builder(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(text), btn -> action.run()).bounds(x, y, w, 16);
		if (tooltip != null) {
			b.tooltip(Tooltip.create(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(tooltip)));
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
			int w = font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(text));
			int at = place(w);
			DirectorScreen.this.label(at, cy, text);
			return this;
		}

		Flow button(String text, String tooltip, Runnable action) {
			int w = font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(text)) + 12;
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
		for (var line : font.split(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(text), w)) {
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
		switch (section) {
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
				JsonObject crs = state != null && state.has("credits") ? state.getAsJsonObject("credits") : new JsonObject();
				if (crs.has(creditsId) && crs.getAsJsonObject(creditsId).has("tempo")) {
					float tempo = crs.getAsJsonObject(creditsId).get("tempo").getAsFloat();
					boolean roll = "roll".equals(kind);
					f.label(roll ? "Tempo " + String.format(java.util.Locale.ROOT, "%.2f", tempo) : String.format(java.util.Locale.ROOT, "%.1f s pro Eintrag", tempo))
							.button("−", roll ? "langsamer" : "kürzer", () -> perform("btb hud credits " + creditsId + " tempo "
									+ String.format(java.util.Locale.ROOT, "%.2f", Math.max(0.05f, roll ? tempo - 0.1f : tempo - 0.5f)), null, true))
							.button("+", roll ? "schneller" : "länger", () -> perform("btb hud credits " + creditsId + " tempo "
									+ String.format(java.util.Locale.ROOT, "%.2f", roll ? tempo + 0.1f : tempo + 0.5f), null, true));
				}

				f.newline().label("Rolle").field(90, creditRole, v -> creditRole = v, "Regie", 80)
						.label("Name").field(90, creditName, v -> creditName = v, "Name", 80)
						.button("Icon: " + (creditIcon.isEmpty() ? "–" : creditIcon), "Klicken zum Wechseln", () -> {
							creditIcon = ICONS.get((ICONS.indexOf(creditIcon) + 1) % ICONS.size());
							rebuildWidgets();
						})
						.fill(0, 110, creditLink, v -> creditLink = v, "Link", 120);
				if (creditEditNr > 0) {
					f.newline().button("✔ Übernehmen (Nr. " + creditEditNr + ")", "Geänderten Eintrag speichern", () -> {
						String entry = creditRole.trim().startsWith("##") ? creditRole.trim()
								: creditRole.trim() + " | " + creditName.trim() + " | " + creditIcon + " | " + creditLink.trim();
						perform("btb hud credits " + creditsId + " edit " + creditEditNr + " " + entry, null, true);
						creditEditNr = 0;
					}).button("Abbrechen", null, () -> {
						creditEditNr = 0;
						rebuildWidgets();
					});
				}
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
				return studioForm(x, top, w);
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
		Button.Builder b = Button.builder(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(key > 0 ? a.label + " §e" + key : a.label), btn -> perform(a.command, fullLabel, a.meta)).bounds(x, y, w, 18);
		// plain words first; the command itself only for those who want it (hold Shift)
		String what = a.tooltip != null ? a.tooltip : fullLabel;
		b.tooltip(Tooltip.create(ClientI18n.c(what + (key > 0 ? "\nTaste: Numpad " + key : "")
				+ (a.command.startsWith("local:") ? "" : "\n§8Shift: Befehl zeigen"))));
		Button built = b.build();
		if (!a.command.startsWith("local:")) {
			buttonCommands.put(built, a.command);
		}
		return built;
	}

	private void cmd(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (command.startsWith("obs:")) {
			de.behindtheblocks.cinematic.client.obs.ObsBridge.command(command.substring(4));
			status = "OBS " + command.substring(4);
			return;
		}
		if (command.startsWith("local:") && localStudio(command)) {
			return;
		}
		if (command.startsWith("local:creditedit:")) {
			int nr = Integer.parseInt(command.substring("local:creditedit:".length()));
			JsonObject cr = state != null && state.has("credits") ? state.getAsJsonObject("credits") : new JsonObject();
			if (cr.has(creditsId) && cr.getAsJsonObject(creditsId).has("raw")) {
				JsonArray raw = cr.getAsJsonObject(creditsId).getAsJsonArray("raw");
				if (nr <= raw.size()) {
					JsonObject e = raw.get(nr - 1).getAsJsonObject();
					creditEditNr = nr;
					creditRole = e.has("heading") ? "## " + e.get("heading").getAsString() : e.has("role") ? e.get("role").getAsString() : "";
					creditName = e.has("name") ? e.get("name").getAsString() : "";
					creditIcon = e.has("icon") ? e.get("icon").getAsString() : "";
					creditLink = e.has("link") ? e.get("link").getAsString() : "";
					status = "Eintrag " + nr + " geladen – ändern und „Übernehmen“ klicken";
				}
			}
			rebuildWidgets();
			return;
		}
		if (command.startsWith("local:cameradel:")) {
			String id = command.substring("local:cameradel:".length());
			if (id.equals(cameraDelete)) {
				cameraDelete = null;
				cmd("btb camera delete " + id);
				status = "Pfad „" + id + "“ gelöscht";
				refreshSoon();
			} else {
				cameraDelete = id;
				status = "Nochmal ✕ bei „" + id + "“ = löschen";
			}
			return;
		}
		if (command.startsWith("local:replaydel:")) {
			String id = command.substring("local:replaydel:".length());
			if (id.equals(replayDelete)) {
				replayDelete = null;
				cmd("btb replay delete " + id);
				status = "Replay „" + id + "“ gelöscht";
				refreshSoon();
			} else {
				replayDelete = id;
				status = "Nochmal ✕ bei „" + id + "“ = löschen";
			}
			return;
		}
		if (command.equals("local:cleanfeed")) {
			DirectorHotkeys.toggleCleanFeed();
			status = "Clean Feed " + (DirectorHotkeys.cleanFeed() ? "an" : "aus");
			return;
		}
		if (command.equals("local:newscene") || command.startsWith("local:edit:")) {
			builderScene = command.startsWith("local:edit:") ? command.substring(11) : "neue_szene";
			currentTab = Tab.SCENES;
			section = Section.BUILDER;
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

	/** The local actions of the new pages; true = handled. */
	private boolean localStudio(String command) {
		String c = command.substring(6);
		statusError = false;
		if (c.startsWith("section:")) {
			section = Section.valueOf(c.substring(8));
			currentTab = section.tab;
			page = 0;
			search = "";
			return true;
		}
		if (c.startsWith("url:")) {
			try {
				net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(c.substring(4)));
			} catch (IllegalArgumentException e) {
				status = "Link ungültig";
				statusError = true;
			}
			return true;
		}
		if (c.equals("searchclear")) {
			search = "";
			return true;
		}
		if (c.equals("close")) {
			onClose();
			return true;
		}
		if (c.startsWith("wizard:")) {
			wizard = c.length() > 7 ? c.substring(7) : null;
			wizardStep = 0;
			if (wizard != null) {
				currentTab = Tab.START;
				section = Section.WIZARD;
			}
			page = 0;
			return true;
		}
		if (c.startsWith("wizstep:")) {
			int d = Integer.parseInt(c.substring(8));
			if (wizard != null && d > 0 && wizardStep + 1 >= wizardSteps(wizard).size()) {
				wizard = null;
				status = "Assistent fertig";
			} else {
				wizardStep = Math.max(0, wizardStep + d);
			}
			return true;
		}
		if (c.startsWith("wizscene:") || c.startsWith("wizscene9:")) {
			String t = c.startsWith("wizscene9:") ? "9" : "0";
			String what = c.substring(c.indexOf(':') + 1);
			cmd("btb scene add mein_film " + t + " " + what);
			status = "→ Szene „mein_film“ bei " + t + " s: " + what;
			return true;
		}
		if (c.startsWith("template:")) {
			String id = c.substring(9);
			cmd("btb vorlage " + id + " szene");
			builderScene = "vorlage_" + id;
			currentTab = Tab.SCENES;
			section = Section.BUILDER;
			page = 0;
			refreshSoon();
			return true;
		}
		if (c.equals("dialogscene")) {
			cmd("btb scene add " + builderScene + " " + time() + " btb dialog " + dialogId + " play");
			status = "→ Szene „" + builderScene + "“ bei " + time() + " s: Dialog „" + dialogId + "“";
			return true;
		}
		if (c.startsWith("pathsel:")) {
			String id = c.substring(8);
			pathSel = id.equals(pathSel) ? null : id;
			previewStart = System.currentTimeMillis();
			currentTab = Tab.CAMERA;
			section = Section.PATHS;
			return true;
		}
		if (c.startsWith("projimport:")) {
			String p = c.substring(11);
			if (p.equals(importArmed)) {
				importArmed = null;
				cmd("btb projekt import " + p);
				refreshSoon();
			} else {
				importArmed = p;
				status = "Nochmal klicken = Projekt „" + p + "“ laden";
			}
			return true;
		}
		if (c.startsWith("shot:")) {
			String[] a = c.substring(5).split(":");
			int i = a.length > 1 ? Integer.parseInt(a[1]) : -1;
			switch (a[0]) {
				case "start" -> {
					StudioClient.reset();
					StudioClient.next();
					StudioClient.shoot(StudioClient.current());
					status = StudioClient.current() >= 0 ? "Dreh läuft: Shot " + (StudioClient.current() + 1) : "Keine Shots";
				}
				case "next" -> {
					StudioClient.next();
					if (StudioClient.current() >= 0) {
						StudioClient.shoot(StudioClient.current());
						status = "Nächster Shot: " + (StudioClient.current() + 1);
					} else {
						status = "Alle Shots im Kasten 🎬";
					}
				}
				case "stop" -> StudioClient.shoot(-1);
				case "reset" -> StudioClient.reset();
				case "run" -> StudioClient.shoot(i);
				case "done" -> StudioClient.toggleDone(i);
				case "up" -> StudioClient.move(i, -1);
				case "del" -> StudioClient.remove(i);
				case "pic" -> StudioClient.captureLater(i);
				case "edit" -> {
					StudioClient.Shot s = StudioClient.shots().get(i);
					shotEdit = i;
					shotTitle = s.title();
					shotNote = s.note();
					shotSeconds = fmt(s.seconds());
					shotCommand = s.command();
					showForm = true;
					formPinned = true;
					status = "Shot " + (i + 1) + " geladen – ändern und „Übernehmen“";
				}
				default -> {
				}
			}
			return true;
		}
		return false;
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

	// ------------------------------------------------------------------ studio pages (new)

	private static String search = "";
	private static boolean searchFocus;
	private static boolean formAuto, formPinned;
	private boolean rebuildSoon;
	private int titleRoom = 400, statusX = 300;
	private final java.util.Map<Button, String> buttonCommands = new java.util.IdentityHashMap<>();
	private static String statusShown = "";
	private static long statusAt;
	private static boolean statusError;
	// storyboard form
	private static int shotEdit = -1;
	private static String shotTitle = "", shotNote = "", shotSeconds = "5", shotCommand = "";
	// dialog form
	private static String dialogId = "dialog1", lineTime = "0", lineDur = "3", lineSpeaker = "", lineDe = "", lineEn = "";
	// crowds
	private static String groupName = "menge", groupCount = "8";
	private static int groupType;
	private static final List<String[]> GROUP_TYPES = List.of(new String[] {"minecraft:villager", "Dorfbewohner"},
			new String[] {"minecraft:piglin", "Piglins"}, new String[] {"minecraft:zombified_piglin", "Zombie-Piglins"},
			new String[] {"minecraft:pillager", "Plünderer"}, new String[] {"minecraft:wither_skeleton", "Wither-Skelette"},
			new String[] {"minecraft:wandering_trader", "Händler"}, new String[] {"minecraft:zombie", "Zombies"});
	// prompter
	private static String promptText = "", promptLead = "3";
	// project
	private static String projectName = "mein_film";
	private static String importArmed;
	// camera paths
	private static String pathSel;
	private static long previewStart = System.currentTimeMillis();
	// wizard
	private static String wizard;
	private static int wizardStep;
	private static String wizardPath = "fahrt1";

	/** Called with each server answer while the menu muted the chat: it shows as a short note in the menu. */
	public static void onServerFeedback(String text, boolean error) {
		if (open != null) {
			String t = text.startsWith("[BTB] ") ? text.substring(6) : text;
			open.status = t;
			statusError = error;
		}
	}

	private static boolean hasForm(Section s) {
		return switch (s) {
			case BUILDER, HUD, CREDITS, RECORDING, REPLAY, DIALOG, STORYBOARD, CROWD, PLAYERS, PROJECT -> true;
			default -> false;
		};
	}

	/** What to do on an empty page: one sentence + the button for the first step. */
	private Row emptyHelp(Section s) {
		return switch (s) {
			case SCENES -> new Row("Noch keine Szenen. Eine Szene ist eine Zeitleiste mit Schritten.", 0xFF888888,
					List.of(new Action("+ Neue Szene", "local:newscene", null, true), new Action("Vorlage nehmen", "local:section:TEMPLATES", null, true)));
			case BUILDER -> new Row("Noch keine Schritte: oben Befehl + Zeit eintragen, oder „● Live aufnehmen“ und inszenieren.", 0xFF888888, List.of());
			case PATHS -> new Row("Noch keine Kamerapfade. 1. „● Pfad aufnehmen“ und fliegen  2. „■ Stopp“", 0xFF888888,
					List.of(new Action("● Pfad aufnehmen", "btb camera record fahrt_" + (System.currentTimeMillis() / 1000 % 100000), null, true),
							new Action("Assistent", "local:wizard:path", "Schritt für Schritt eine Kamerafahrt bauen", true)));
			case STORYBOARD -> new Row("Noch keine Shots. Oben Titel, Notiz und Länge eintragen, „+ Shot“.", 0xFF888888, List.of());
			case DIALOG -> new Row("Noch keine Zeilen. Oben Zeit, Sprecher und Text eintragen, „+ Zeile“.", 0xFF888888, List.of());
			case CROWD -> new Row("Noch keine Gruppen. Oben Typ und Anzahl wählen, „+ Hier spawnen“.", 0xFF888888, List.of());
			case REPLAY -> new Row("Noch keine Replays. Oben Namen eintippen, „● Aufnehmen“, spielen, „■ Stopp“.", 0xFF888888,
					List.of(new Action("Assistent", "local:wizard:replay", "Replay aufnehmen und exportieren, Schritt für Schritt", true)));
			case HUD -> new Row("Noch keine gespeicherten Texte. Oben Text eintippen und einen Stil anklicken.", 0xFF888888, List.of());
			case PORTALS -> new Row("Noch keine Portale – anlegen per /btb portal create (siehe Doku).", 0xFF888888, List.of());
			case DESTRUCTION -> new Row("Noch keine Zerstörungsbereiche – anlegen per /btb destruction create (siehe Doku).", 0xFF888888, List.of());
			case SHOWCASE -> new Row("Noch keine Showcases – anlegen per /btb showcase create (siehe Doku).", 0xFF888888, List.of());
			case PROJECT -> new Row("Noch keine Versionen: sie entstehen bei jeder Änderung von selbst.", 0xFF888888, List.of());
			default -> null;
		};
	}

	private void studioContent(Section sec, List<Row> rows, List<Action> header) {
		JsonObject studio = studio();
		switch (sec) {
			case START -> {
				JsonObject rp = state != null && state.has("replays") ? state.getAsJsonObject("replays") : new JsonObject();
				String rec = rp.has("recording") ? rp.get("recording").getAsString() : "";
				String play = rp.has("playing") ? rp.get("playing").getAsString() : "";
				rows.add(new Row("Schnellstart", 0xFFFFE0C0, List.of(
						new Action("+ Neue Szene", "local:newscene", "Szene im Baukasten anlegen", true),
						new Action(rec.isEmpty() ? "● Replay aufnehmen" : "■ Replay stoppen", rec.isEmpty() ? "btb replay record take_" + (System.currentTimeMillis() / 1000 % 100000) : "btb replay stop",
								"Alles, was jetzt passiert, als Replay speichern (danach beliebig oft filmen)", true),
						new Action("Kamera stop", "btb camera stop @a", "Zurück zum eigenen Blick (alle Spieler)"))));
				rows.add(new Row("Assistenten (Schritt für Schritt)", 0xFF9CE0FF, List.of(
						new Action("Ersten Film drehen", "local:wizard:film", null, true),
						new Action("Replay & Export", "local:wizard:replay", null, true),
						new Action("Kamerafahrt bauen", "local:wizard:path", null, true))));
				if (!rec.isEmpty()) {
					rows.add(new Row("● Replay „" + rec + "“ nimmt auf", 0xFFFF6060, List.of(new Action("■ Stopp & speichern", "btb replay stop", null, true))));
				}
				if (!play.isEmpty()) {
					rows.add(new Row("▶ Replay „" + play + "“ läuft", 0xFF7CFF7C, List.of(new Action("⏸", "btb replay pause", "Pause / weiter", true),
							new Action("■ Stopp", "btb replay stop", null, true))));
				}
				for (JsonObject s : objects("scenes")) {
					String st = s.get("state").getAsString();
					if (!st.equals("stopped")) {
						String id = s.get("id").getAsString();
						rows.add(new Row("▶ Szene " + id + " · " + fmt(s.get("tick").getAsInt() / 20.0) + " / " + fmt(s.get("duration").getAsInt() / 20.0) + " s",
								0xFF7CFF7C, List.of(new Action(st.equals("paused") ? "⏵" : "⏸", "btb scene " + (st.equals("paused") ? "resume " : "pause ") + id, "Pause / Fortsetzen"),
								new Action("■", "btb scene stop " + id, "Stoppen"))));
					}
				}
				List<StudioClient.Shot> shots = StudioClient.shots();
				int cur = StudioClient.current();
				if (cur >= 0 && cur < shots.size()) {
					StudioClient.Shot sh = shots.get(cur);
					rows.add(new Row("🎬 Shot " + (cur + 1) + "/" + shots.size() + ": " + sh.title() + " · " + fmt(sh.seconds()) + " s", 0xFFFFC080, List.of(
							new Action("▶ Nochmal", "local:shot:run:" + cur, "Aktion des Shots erneut starten", true),
							new Action("✓ Weiter", "local:shot:next", "Shot ist im Kasten → nächster", true))));
				} else if (!shots.isEmpty()) {
					long done = shots.stream().filter(StudioClient.Shot::done).count();
					rows.add(new Row("Storyboard: " + done + "/" + shots.size() + " Shots gedreht", 0xFFFFC080, List.of(
							new Action("🎬 Dreh starten", "local:shot:start", "Beim ersten offenen Shot beginnen", true),
							new Action("Storyboard", "local:section:STORYBOARD", null, true))));
				}
				int undo = studio.has("undo") ? studio.get("undo").getAsInt() : 0;
				if (undo > 0) {
					rows.add(new Row("Letzte Änderung: " + studio.get("undo_next").getAsString(), 0xFFBBBBBB, List.of(
							new Action("↶ Rückgängig", "btb undo", "Strg+Z", true))));
				}
				rows.add(new Row("Set", 0xFFBBBBBB, List.of(new Action("■ Alles stoppen", "btb stop", "Stoppt alles, was läuft – das Set bleibt", true),
						new Action("⟲ Set zurücksetzen", "btb resetall", "Szenen, Portale, Zerstörung, Effekte: alles auf Anfang", true))));
			}
			case WIZARD -> wizardRows(rows);
			case INFO -> {
				String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(BtbCinematic.MOD_ID)
						.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
				rows.add(new Row("§lBTB Cinematics§r · v" + version, 0xFFFFE0C0, List.of()));
				rows.add(new Row("Entwickelt von §6Nik2810", 0xFFFFFFFF, List.of()));
				rows.add(new Row("Für die Minecraft-Filmproduktion „Behind The Blocks“", 0xFFDDDDDD, List.of()));
				rows.add(new Row("Quellcode, Anleitung und Fehler melden", 0xFFDDDDDD, List.of(
						new Action("GitHub", "local:url:https://github.com/nik0bellic/btb-cinematics", "github.com/nik0bellic/btb-cinematics", true))));
				rows.add(new Row("Behind The Blocks", 0xFFDDDDDD, List.of(
						new Action("Instagram", "local:url:https://www.instagram.com/btb.minecraft", "@btb.minecraft", true),
						new Action("TikTok", "local:url:https://www.tiktok.com/@btb.plus", "@btb.plus", true))));
				rows.add(new Row("Lizenz: LGPL-3.0-only · Schrift Cinzel: SIL Open Font License 1.1", 0xFF999999, List.of()));
				rows.add(new Row("Danke fürs Benutzen – viel Spaß beim Drehen! 🎬", 0xFF9CE0FF, List.of()));
			}
			case TEMPLATES -> {
				JsonArray tp = studio.has("template_info") ? studio.getAsJsonArray("template_info") : new JsonArray();
				for (var el : tp) {
					JsonObject t = el.getAsJsonObject();
					String id = t.get("id").getAsString();
					rows.add(new Row(t.get("title").getAsString() + " – " + t.get("description").getAsString(), 0xFFFFE0C0, List.of(
							new Action("▶ Jetzt", "btb vorlage " + id, "Mit dem nächsten Darsteller (oder dir) sofort abspielen"),
							new Action("→ Szene", "local:template:" + id, "Als bearbeitbare Szene „vorlage_" + id + "“ anlegen und im Baukasten öffnen", true))));
				}
			}
			case STORYBOARD -> {
				List<StudioClient.Shot> shots = StudioClient.shots();
				int cur = StudioClient.current();
				header.add(new Action(cur >= 0 ? "✓ Weiter" : "🎬 Dreh starten", cur >= 0 ? "local:shot:next" : "local:shot:start",
						cur >= 0 ? "Aktueller Shot ist im Kasten → nächster offener Shot" : "Beim ersten offenen Shot beginnen", true));
				if (cur >= 0) {
					header.add(new Action("■ Dreh beenden", "local:shot:stop", null, true));
				}
				header.add(new Action("Alle offen", "local:shot:reset", "Alle Haken entfernen", true));
				for (int i = 0; i < shots.size(); i++) {
					StudioClient.Shot sh = shots.get(i);
					String mark = i == cur ? "▶ " : sh.done() ? "✓ " : "   ";
					rows.add(new Row(mark + (i + 1) + ". " + sh.title() + " · " + fmt(sh.seconds()) + " s" + (sh.note().isBlank() ? "" : " – " + sh.note()),
							i == cur ? 0xFFFFC080 : sh.done() ? 0xFF7CFF7C : 0xFFFFFFFF, List.of(
							new Action("▶", "local:shot:run:" + i, sh.command().isBlank() ? "Diesen Shot jetzt drehen" : "Shot drehen + Aktion: /" + sh.command(), true),
							new Action("📷 Bild", "local:shot:pic:" + i, "Aktuelle Ansicht als Bild für diesen Shot (Menü schließt kurz)", true),
							new Action(sh.done() ? "↺" : "✓", "local:shot:done:" + i, sh.done() ? "Wieder offen" : "Als gedreht abhaken", true),
							new Action("✎", "local:shot:edit:" + i, "In die Felder oben laden", true),
							new Action("↑", "local:shot:up:" + i, null, true),
							new Action("✕", "local:shot:del:" + i, "Shot löschen", true))));
				}
			}
			case DIALOG -> {
				JsonObject dialogs = studio.has("dialogs") ? studio.getAsJsonObject("dialogs") : new JsonObject();
				header.add(new Action("▶ Abspielen", "btb dialog " + dialogId + " play", "Untertitel nach Zeit zeigen (jeder Spieler in seiner Sprache), Sprecher bewegen sich"));
				header.add(new Action("■", "btb dialog stop", "Dialog stoppen"));
				header.add(new Action(".srt", "btb dialog " + dialogId + " srt", "Untertitel-Dateien (Deutsch + Englisch) für den Videoschnitt speichern", true));
				header.add(new Action("→ Szene", "local:dialogscene", "Abspielen als Schritt in die Baukasten-Szene bei der Zeit „Bei“", true));
				if (dialogs.has(dialogId)) {
					JsonArray lines = dialogs.getAsJsonArray(dialogId);
					for (int i = 0; i < lines.size(); i++) {
						JsonObject l = lines.get(i).getAsJsonObject();
						int nr = i + 1;
						String who = l.has("sprecher") && !l.get("sprecher").getAsString().isBlank() ? l.get("sprecher").getAsString() + ": " : "";
						boolean german = de.behindtheblocks.cinematic.client.i18n.ClientI18n.german();
						String text = german || !l.has("en") ? l.get("de").getAsString() : l.get("en").getAsString();
						rows.add(new Row(nr + ".  " + fmt(l.get("t").getAsDouble()) + " s (" + fmt(l.get("dauer").getAsDouble()) + " s)  " + who + text, 0xFFFFFFFF, List.of(
								new Action("−½", "btb dialog " + dialogId + " shift " + nr + " -0.5", "½ Sekunde früher", true),
								new Action("+½", "btb dialog " + dialogId + " shift " + nr + " 0.5", "½ Sekunde später", true),
								new Action("✕", "btb dialog " + dialogId + " remove " + nr, "Zeile löschen", true))));
					}
				}
			}
			case PATHS -> {
				header.add(new Action("● Pfad aufnehmen", "btb camera record fahrt_" + (System.currentTimeMillis() / 1000 % 100000), "Im Zuschauermodus die Fahrt fliegen, dann „■ Stopp“", true));
				header.add(new Action("■ Stopp", "btb camera record stop", "Aufnahme beenden: Pfad wird geglättet und gespeichert", true));
				header.add(new Action("+ Neuer Pfad", "btb camera create pfad_" + (System.currentTimeMillis() / 1000 % 100000), "Neuer Kamerapfad, aktuelle Ansicht = Punkt 1", true));
				header.add(new Action("Vorschau aus", "btb camera preview off", "Alle Linien in der Welt ausblenden", true));
				JsonObject paths = state != null && state.has("camera_paths") ? state.getAsJsonObject("camera_paths") : new JsonObject();
				for (String id : paths.keySet()) {
					JsonObject p = paths.getAsJsonObject(id);
					boolean pv = p.get("preview").getAsBoolean();
					boolean sel = id.equals(pathSel);
					boolean armed = id.equals(cameraDelete);
					rows.add(new Row((sel ? "▼ " : "") + id + " · " + p.getAsJsonArray("points").size() + " Punkte · " + fmt(p.get("seconds").getAsDouble()) + " s",
							sel ? 0xFFFFC080 : 0xFFFFFFFF, List.of(
							new Action("▶ ich", "btb camera play " + id, "Fahrt für mich abspielen"),
							new Action("▶ alle", "btb camera play " + id + " @a", "Fahrt für alle Spieler"),
							new Action("■", "btb camera stop @a", "Fahrt stoppen"),
							new Action(pv ? "👁 an" : "👁", "btb camera preview " + (pv ? "off" : id + " an"), "Linie + Punkte in der Welt zeigen (aktualisiert sich bei Änderungen)", true),
							new Action(sel ? "Punkte ▴" : "Punkte ▾", "local:pathsel:" + id, "Punkte bearbeiten + Draufsicht", true),
							new Action(armed ? "Sicher?" : "✕", "local:cameradel:" + id, armed ? "Nochmal klicken = endgültig löschen" : "Pfad löschen", true))));
					if (sel) {
						JsonArray pts = p.getAsJsonArray("points");
						for (int i = 0; i < pts.size(); i++) {
							JsonArray pt = pts.get(i).getAsJsonArray();
							int nr = i + 1;
							double dur = pt.get(4).getAsDouble();
							rows.add(new Row("    Punkt " + nr + " · " + (i < pts.size() - 1 ? fmt(dur) + " s bis zum nächsten" : "Ende"), 0xFFDDDDDD, List.of(
									new Action("Hin", "btb camera goto " + id + " " + nr, "Dorthin teleportieren und so schauen wie die Kamera", true),
									new Action("Hierher", "btb camera point " + id + " " + nr, "Punkt auf deine Position + Blickrichtung setzen", true),
									new Action("−½s", "btb camera duration " + id + " " + nr + " " + fmt(Math.max(0.1, dur - 0.5)), "Schneller zum nächsten Punkt", true),
									new Action("+½s", "btb camera duration " + id + " " + nr + " " + fmt(dur + 0.5), "Langsamer zum nächsten Punkt", true),
									new Action("✕", "btb camera remove " + id + " " + nr, "Punkt löschen", true))));
						}
						rows.add(new Row("    + Punkt anhängen (deine Ansicht)", 0xFFDDDDDD, List.of(new Action("+ Punkt", "btb camera point " + id, null, true))));
					}
				}
			}
			case CROWD -> {
				JsonObject groups = studio.has("groups") ? studio.getAsJsonObject("groups") : new JsonObject();
				for (String g : groups.keySet()) {
					rows.add(new Row("Gruppe " + g + " · " + groups.get(g).getAsInt(), 0xFFFFE0C0, List.of(
							new Action("Zu mir", "btb gruppe " + g + " laufen ~ ~ ~", "Alle laufen zu dir (Formation bleibt)"),
							new Action("Dorthin", "execute anchored eyes positioned ^ ^ ^14 run btb gruppe " + g + " laufen ~ ~ ~", "Zu dem Punkt, auf den du schaust (14 Blöcke)"),
							new Action("Fliehen", "btb gruppe " + g + " fliehen", "Rennen von dir weg"),
							new Action("Jubeln", "btb gruppe " + g + " jubeln 5", "Springen und winken"),
							new Action("Herschauen", "btb gruppe " + g + " schauen ~ ~1.6 ~", "Alle schauen zu dir"),
							new Action("Stehen", "btb gruppe " + g + " stehen", null),
							new Action("✕", "btb gruppe " + g + " weg", "Gruppe entfernen", true))));
				}
			}
			case WEATHER -> {
				rows.add(new Row("Tageszeit sofort", 0xFFFFFFFF, List.of(new Action("Morgen", "btb welt zeit morgen", null), new Action("Mittag", "btb welt zeit mittag", null),
						new Action("Abend", "btb welt zeit abend", null), new Action("Nacht", "btb welt zeit nacht", null))));
				rows.add(new Row("Tageszeit als Übergang", 0xFFFFFFFF, List.of(
						new Action("→ Sonnenuntergang 20 s", "btb welt zeit abend 20", "Die Sonne geht in 20 Sekunden unter"),
						new Action("→ Nacht 15 s", "btb welt zeit nacht 15", null),
						new Action("→ Morgen 20 s", "btb welt zeit morgen 20", "Sonnenaufgang"))));
				rows.add(new Row("Wetter", 0xFFFFFFFF, List.of(new Action("Klar", "btb welt wetter klar", null),
						new Action("Regen", "btb welt wetter regen", null), new Action("Gewitter", "btb welt wetter gewitter", null))));
				rows.add(new Row("Hinweis: Tageszeit und Wetter gibt es nur in der Oberwelt (nicht im Nether).", 0xFF888888, List.of()));
			}
			case REPLAY -> {
				JsonObject rp = state != null && state.has("replays") ? state.getAsJsonObject("replays") : new JsonObject();
				String recording = rp.has("recording") ? rp.get("recording").getAsString() : "";
				String playing = rp.has("playing") ? rp.get("playing").getAsString() : "";
				header.add(new Action("Export abbrechen", "btb export stop", null, true));
				if (!recording.isEmpty()) {
					rows.add(new Row("● Replay „" + recording + "“ nimmt auf", 0xFFFF6060, List.of(
							new Action("■ Stopp & speichern", "btb replay stop", null, true))));
				} else if (!playing.isEmpty()) {
					rows.add(new Row("▶ Replay „" + playing + "“ läuft", 0xFF7CFF7C, List.of(
							new Action("⏸", "btb replay pause", "Pause / weiter", true),
							new Action("■ Stopp", "btb replay stop", null, true))));
				}
				JsonArray takes = rp.has("list") ? rp.getAsJsonArray("list") : new JsonArray();
				for (var el : takes) {
					JsonObject t = el.getAsJsonObject();
					String id = t.get("id").getAsString();
					double secs = t.get("seconds").getAsDouble();
					boolean armed = id.equals(replayDelete);
					rows.add(new Row(String.format(java.util.Locale.ROOT, "Replay %s · %.1f s", id, secs), id.equals(playing) ? 0xFF7CFF7C : 0xFFFF9070, List.of(
							new Action("▶", "btb replay play " + id, "Abspielen: Darsteller werden kurz Zuschauer, Puppen spielen"),
							new Action("✂ Editor", "btb replay editor " + id, "Timeline wie ReplayMod: spulen, Kamera-Keyframes, mehrere Kameras, Export (F6)"),
							new Action("1080p", "btb replay export " + id + " 1080p 60", "Ganzes Replay als Video exportieren (1080p, 60 FPS)"),
							new Action("4K", "btb replay export " + id + " 4k 60", "Ganzes Replay als Video exportieren (4K, 60 FPS)"),
							new Action(armed ? "Sicher?" : "✕", "local:replaydel:" + id, armed ? "Nochmal klicken = endgültig löschen" : "Replay löschen", true))));
				}
			}
			case PROJECT -> {
				JsonArray pr = studio.has("projects") ? studio.getAsJsonArray("projects") : new JsonArray();
				for (var el : pr) {
					String p = el.getAsString();
					boolean armed = p.equals(importArmed);
					rows.add(new Row("Projekt " + p, 0xFFFFE0C0, List.of(new Action(armed ? "Sicher? Laden" : "Laden", "local:projimport:" + p,
							armed ? "Nochmal klicken: Szenen, Pfade, Texte … dieses Projekts übernehmen (alte Stände bleiben als Versionen)"
									: "Projekt laden (zweimal klicken)", true))));
				}
				JsonArray vs = studio.has("versions") ? studio.getAsJsonArray("versions") : new JsonArray();
				for (var el : vs) {
					JsonObject v = el.getAsJsonObject();
					String key = v.get("key").getAsString();
					JsonArray list = v.getAsJsonArray("versions");
					List<Action> acts = new ArrayList<>();
					for (int i = list.size() - 1; i >= Math.max(0, list.size() - 3); i--) {
						String ver = list.get(i).getAsString();
						String when = ver.length() >= 16 ? ver.substring(11, 16).replace('-', ':') : ver;
						acts.add(new Action("⟲ " + when, "btb versionen " + key.replace('/', '.') + " wiederherstellen \"" + ver + "\"",
								"Stand vom " + ver.substring(0, Math.min(10, ver.length())) + " " + when + " zurückholen (der jetzige bleibt als Version)", true));
					}
					rows.add(new Row(key, 0xFFDDDDDD, acts));
				}
			}
			default -> {
			}
		}
		if (sec == Section.PLAYERS) {
			JsonObject roles = studio.has("roles") ? studio.getAsJsonObject("roles") : new JsonObject();
			for (String name : roles.keySet()) {
				String r = roles.get(name).getAsString();
				rows.add(0, new Row(name + " · Rolle: " + (r.isEmpty() ? "–" : r), 0xFF9CE0FF, List.of(
						new Action(r.equals("regie") ? "▸Regie" : "Regie", "btb rolle " + name + " regie", null, true),
						new Action(r.equals("kamera") ? "▸Kamera" : "Kamera", "btb rolle " + name + " kamera", "Kamerafahrten der Szenen laufen bei diesem Spieler", true),
						new Action(r.equals("darsteller") ? "▸Darsteller" : "Darsteller", "btb rolle " + name + " darsteller", "Bekommt Hinweise („In 3 s: nach links“) eingeblendet", true),
						new Action("–", "btb rolle " + name + " aus", "Keine Rolle", true))));
			}
		}
	}

	/** Forms of the new pages. */
	private int studioForm(int x, int top, int w) {
		switch (section) {
			case REPLAY -> {
				Flow f = new Flow(x, top, w);
				f.label("Replay").field(90, replayName, v -> replayName = v, "Name, z. B. take2", 40)
						.button("● Aufnehmen", "Alle Darsteller, Kreaturen, Szenen und Ereignisse aufnehmen", () -> {
							String n = replayName.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
							if (n.isEmpty()) {
								status = "Erst einen Namen eintippen";
								statusError = true;
								rebuildWidgets();
								return;
							}
							perform("btb replay record " + n, "● Replay " + n, true);
						})
						.button("■ Stopp", "Aufnahme beenden und speichern / Replay stoppen", () -> perform("btb replay stop", "■ Replay", true))
						.button("✂ Editor", "Replay-Editor öffnen (F6)", () -> perform("btb replay editor", "✂ Editor", true));
				return f.end();
			}
			case STORYBOARD -> {
				Flow f = new Flow(x, top, w);
				f.label("Shot").field(110, shotTitle, v -> shotTitle = v, "Titel, z. B. Verfolgung", 60)
						.field(30, shotSeconds, v -> shotSeconds = v.replace(',', '.'), "s", 6).label("s")
						.fill(0, 100, shotNote, v -> shotNote = v, "Notiz (Kamera, Darsteller …)", 120);
				f.newline().label("Aktion").fill(150, 110, shotCommand, v -> shotCommand = v, "optional: Befehl, z. B. btb scene restart film_03", 200)
						.button(shotEdit >= 0 ? "✔ Übernehmen" : "+ Shot", shotEdit >= 0 ? "Geänderten Shot speichern" : "Shot ans Ende des Storyboards", () -> {
							if (shotTitle.isBlank()) {
								status = "Erst einen Titel eintippen";
								statusError = true;
								rebuildWidgets();
								return;
							}
							double sec = Double.parseDouble(seconds(shotSeconds, "5"));
							if (shotEdit >= 0) {
								StudioClient.update(shotEdit, shotTitle.trim(), shotNote.trim(), sec, strip(shotCommand));
								status = "Shot " + (shotEdit + 1) + " geändert";
							} else {
								StudioClient.add(shotTitle.trim(), shotNote.trim(), sec, strip(shotCommand));
								status = "Shot hinzugefügt";
							}
							statusError = false;
							shotEdit = -1;
							shotTitle = shotNote = shotCommand = "";
							rebuildWidgets();
						});
				if (shotEdit >= 0) {
					f.button("Abbrechen", null, () -> {
						shotEdit = -1;
						shotTitle = shotNote = shotCommand = "";
						rebuildWidgets();
					});
				}
				return f.end();
			}
			case DIALOG -> {
				JsonObject dialogs = studio().has("dialogs") ? studio().getAsJsonObject("dialogs") : new JsonObject();
				List<String> ids = new ArrayList<>(dialogs.keySet());
				Flow f = new Flow(x, top, w);
				f.label("Dialog").button("◀", null, () -> {
					dialogId = cycle(ids, dialogId, -1);
					rebuildWidgets();
				}).field(80, dialogId, v -> dialogId = v.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), "name", 40).button("▶", null, () -> {
					dialogId = cycle(ids, dialogId, 1);
					rebuildWidgets();
				}).label("Bei").field(34, lineTime, v -> lineTime = v.replace(',', '.'), "s", 8)
						.label("Dauer").field(30, lineDur, v -> lineDur = v.replace(',', '.'), "s", 6)
						.label("Sprecher").field(80, lineSpeaker, v -> lineSpeaker = v, "Name", 40);
				f.newline().label("DE").fill(0, 120, lineDe, v -> lineDe = v, "Deutscher Text", 200);
				f.newline().label("EN").fill(64, 120, lineEn, v -> lineEn = v, "English text (optional)", 200)
						.button("+ Zeile", "Zeile in den Dialog. Sprecher = Name eines Statisten/Spielers: er bewegt beim Sprechen Kopf und Arme", () -> {
							if (lineDe.isBlank() || dialogId.isBlank()) {
								status = "Erst Text eingeben";
								statusError = true;
								rebuildWidgets();
								return;
							}
							perform("btb dialog " + dialogId + " add " + seconds(lineTime, "0") + " " + seconds(lineDur, "3") + " "
									+ lineSpeaker.trim().replace("|", "/") + " | " + lineDe.trim().replace("|", "/") + " | " + lineEn.trim().replace("|", "/"), null, true);
							// next line starts after this one
							try {
								lineTime = fmt(Double.parseDouble(seconds(lineTime, "0")) + Double.parseDouble(seconds(lineDur, "3")) + 0.3);
							} catch (NumberFormatException ignored) {
								// keep
							}
							lineDe = "";
							lineEn = "";
						});
				return f.end();
			}
			case CROWD -> {
				Flow f = new Flow(x, top, w);
				f.label("Gruppe").field(70, groupName, v -> groupName = v.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), "name", 30)
						.button("Typ: " + GROUP_TYPES.get(groupType)[1], "Klicken zum Wechseln", () -> {
							groupType = (groupType + 1) % GROUP_TYPES.size();
							rebuildWidgets();
						})
						.field(28, groupCount, v -> groupCount = v, "8", 3).label("Stück")
						.button("+ Hier spawnen", "Statisten um dich herum; sie kämpfen nicht und bewegen sich nur auf Kommando", () -> {
							int n;
							try {
								n = Math.max(1, Math.min(60, Integer.parseInt(groupCount.trim())));
							} catch (NumberFormatException e) {
								n = 8;
							}
							perform("btb gruppe " + groupName + " spawn " + GROUP_TYPES.get(groupType)[0] + " " + n + " 5", null, true);
						});
				return f.end();
			}
			case PLAYERS -> {
				Flow f = new Flow(x, top, w);
				f.label("Hinweis").fill(140, 120, promptText, v -> promptText = v, "z. B. Nach links laufen", 120)
						.field(24, promptLead, v -> promptLead = v.replace(',', '.'), "3", 4).label("s")
						.button("Senden", "Alle Darsteller sehen: „In 3 … 2 … 1: Text“", () -> {
							if (!promptText.isBlank()) {
								perform("btb hinweis darsteller " + seconds(promptLead, "3") + " " + promptText.trim(), null, true);
							}
						})
						.button("→ Szene", "Als Schritt in die Baukasten-Szene bei der Zeit „Bei“", () -> {
							if (!promptText.isBlank()) {
								cmd("btb scene add " + builderScene + " " + time() + " btb hinweis darsteller " + seconds(promptLead, "3") + " " + promptText.trim());
								status = "→ Szene „" + builderScene + "“ bei " + time() + " s";
							}
						});
				return f.end();
			}
			case PROJECT -> {
				Flow f = new Flow(x, top, w);
				f.label("Projekt").field(100, projectName, v -> projectName = v.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), "name", 40)
						.button("Speichern (.zip)", "Szenen, Kamerapfade, Texte, Credits, Dialoge, Replays … als eine Datei in config/btb_cinematic/projekte", () ->
								perform("btb projekt export " + projectName, null, true))
						.button("↶ Rückgängig", "Letzte Änderung zurücknehmen (Strg+Z)", () -> perform("btb undo", null, true));
				return f.end();
			}
			default -> {
				return top;
			}
		}
	}

	// ------------------------------------------------------------------ wizards

	private record Step(String title, String text, List<Action> actions) {
	}

	private List<Step> wizardSteps(String id) {
		String scene = "mein_film";
		return switch (id) {
			case "film" -> List.of(
					new Step("1. Eine Szene anlegen", "Eine Szene ist eine Zeitleiste: was zu welcher Sekunde passiert. Wir legen „" + scene + "“ an.",
							List.of(new Action("Szene anlegen", "btb scene create " + scene, null, true))),
					new Step("2. Kamera festlegen", "Nimm eine fertige Kamera-Vorlage auf den nächsten Darsteller – sie landet als Schritt in deiner Szene.",
							List.of(new Action("Establishing", "local:wizscene:camera orbit ~ ~ ~ 20 10 10 180 @a[tag=kamera]", "Weiter Kreis um die Stelle, an der du stehst", true),
									new Action("Verfolger", "local:wizscene:camera auto @p[tag=!kamera] 5 @a[tag=kamera]", "Auto-Regie auf den nächsten Darsteller", true))),
					new Step("3. Bild gestalten", "Kinobalken und eine Abblende am Ende machen sofort Kino daraus.",
							List.of(new Action("Kinobalken", "local:wizscene:effects letterbox 0.12 1", null, true),
									new Action("Abblende bei 9 s", "local:wizscene9:effects fade black 1", null, true))),
					new Step("4. Aufnehmen", "Starte OBS (Einstellungen → OBS & Tasten) oder nimm ein Replay auf, dann die Szene abspielen.",
							List.of(new Action("OBS Start", "obs:start", null, true),
									new Action("● Replay", "btb replay record " + scene, "Alles aufnehmen, später beliebig oft filmen", true),
									new Action("▶ Szene", "btb scene restart " + scene, null, true))),
					new Step("5. Fertig", "Stoppen, ansehen, im Baukasten Zeiten anpassen. Alles lässt sich mit ↶ Rückgängig zurücknehmen.",
							List.of(new Action("■ Alles stoppen", "btb stop", null, true), new Action("Baukasten", "local:edit:" + scene, null, true))));
			case "replay" -> List.of(
					new Step("1. Aufnahme starten", "Alles, was ab jetzt passiert (Darsteller, Kreaturen, Szenen, Effekte), wird gespeichert.",
							List.of(new Action("● Aufnehmen", "btb replay record take", null, true))),
					new Step("2. Spielen", "Menü schließen und die Szene spielen. Danach wieder öffnen (F7).",
							List.of(new Action("Menü schließen", "local:close", null, true))),
					new Step("3. Aufnahme beenden", "Das Replay „take“ wird gespeichert.",
							List.of(new Action("■ Stopp & speichern", "btb replay stop", null, true))),
					new Step("4. Kamera setzen", "Im Editor spulen, Kamera-Keyframes setzen (K), mehrere Kameras anlegen und schneiden.",
							List.of(new Action("✂ Editor öffnen", "btb replay editor take", null, true))),
					new Step("5. Exportieren", "Bildgenauer Export als Video (ffmpeg nötig).",
							List.of(new Action("1080p", "btb replay export take 1080p 60", null, true), new Action("4K", "btb replay export take 4k 60", null, true))));
			default -> List.of(
					new Step("1. Pfad anlegen", "Stell dich an den Startpunkt, schau in die gewünschte Richtung.",
							List.of(new Action("Pfad „" + wizardPath + "“ anlegen", "btb camera create " + wizardPath, null, true))),
					new Step("2. Punkte setzen", "Menü schließen, zum nächsten Punkt fliegen, F7, „+ Punkt“. Beliebig oft.",
							List.of(new Action("+ Punkt", "btb camera point " + wizardPath, null, true), new Action("Menü schließen", "local:close", null, true))),
					new Step("3. Ansehen", "Die Fahrt als Linie in der Welt sehen und abspielen.",
							List.of(new Action("👁 Vorschau", "btb camera preview " + wizardPath + " an", null, true),
									new Action("▶ Abspielen", "btb camera play " + wizardPath, null, true))),
					new Step("4. Feinschliff", "In „Kamerapfade“ Punkte verschieben, Zeiten ändern, löschen.",
							List.of(new Action("Kamerapfade", "local:pathsel:" + wizardPath, null, true))));
		};
	}

	private void wizardRows(List<Row> rows) {
		if (wizard == null) {
			rows.add(new Row("Ersten Film drehen – Szene, Kamera, Bild, Aufnahme", 0xFF9CE0FF, List.of(new Action("Start", "local:wizard:film", null, true))));
			rows.add(new Row("Replay aufnehmen und exportieren", 0xFF9CE0FF, List.of(new Action("Start", "local:wizard:replay", null, true))));
			rows.add(new Row("Kamerafahrt bauen", 0xFF9CE0FF, List.of(new Action("Start", "local:wizard:path", null, true))));
			return;
		}
		List<Step> steps = wizardSteps(wizard);
		wizardStep = Math.max(0, Math.min(wizardStep, steps.size() - 1));
		Step st = steps.get(wizardStep);
		rows.add(new Row("§l" + st.title + "§r  (" + (wizardStep + 1) + "/" + steps.size() + ")", 0xFFFFC080, List.of(
				new Action("◀", "local:wizstep:-1", "Zurück", true),
				new Action(wizardStep + 1 < steps.size() ? "Weiter ▶" : "✔ Fertig", "local:wizstep:1", null, true),
				new Action("✕", "local:wizard:", "Assistent beenden", true))));
		for (String line : font.split(ClientI18n.c(st.text), 300).stream().map(fl -> {
			StringBuilder sb = new StringBuilder();
			fl.accept((i, style, cp) -> {
				sb.appendCodePoint(cp);
				return true;
			});
			return sb.toString();
		}).toList()) {
			rows.add(new Row(line, 0xFFDDDDDD, List.of()));
		}
		rows.add(new Row("Jetzt:", 0xFFFFFFFF, st.actions));
	}

	// ------------------------------------------------------------------ timeline (builder) + top view (camera path)

	private static int tabScroll;
	private int[] tabListArea;

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (tabListArea != null && mx >= tabListArea[0] && mx <= tabListArea[2] && my >= tabListArea[1] && my <= tabListArea[3] && sy != 0) {
			tabScroll = Math.max(0, tabScroll + (sy > 0 ? -2 : 2));
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	private int timelineX, timelineY, timelineW;
	private int dragEvent = -1;
	private double dragTime;
	private int pathViewX, pathViewY, pathViewW, pathViewH;

	/** Space above the list for the builder timeline or the camera path top view. */
	private int extraTop(int x, int top, int w) {
		timelineW = 0;
		pathViewW = 0;
		if (!search.isBlank()) {
			return top;
		}
		if (section == Section.BUILDER && sceneEvents().size() > 0) {
			timelineX = x;
			timelineY = top;
			timelineW = w;
			addRenderableWidget(Button.builder(ClientI18n.c("▶ ab „Bei“"), b -> perform("btb scene restart " + builderScene + " from " + time(), null, true))
					.tooltip(Tooltip.create(ClientI18n.c("Szene ab der Zeit im Feld „Bei“ abspielen (Klick in die Zeitleiste setzt sie)")))
					.bounds(x + w - 64, top + 24, 64, 14).build());
			return top + 42;
		}
		if (section == Section.PATHS && pathSel != null && state != null && state.has("camera_paths")
				&& state.getAsJsonObject("camera_paths").has(pathSel)) {
			pathViewX = x;
			pathViewY = top;
			pathViewW = Math.min(w, 240);
			pathViewH = 70;
			return top + pathViewH + 6;
		}
		return top;
	}

	private JsonArray sceneEvents() {
		JsonObject ev = state != null && state.has("scene_events") ? state.getAsJsonObject("scene_events") : new JsonObject();
		return ev.has(builderScene) ? ev.getAsJsonArray(builderScene) : new JsonArray();
	}

	private double sceneLength() {
		for (JsonObject s : objects("scenes")) {
			if (s.get("id").getAsString().equals(builderScene)) {
				return Math.max(1, s.get("duration").getAsInt() / 20.0);
			}
		}
		double max = 1;
		for (var el : sceneEvents()) {
			max = Math.max(max, el.getAsJsonObject().get("t").getAsDouble() + 2);
		}
		return max;
	}

	private double sceneTick() {
		for (JsonObject s : objects("scenes")) {
			if (s.get("id").getAsString().equals(builderScene) && !s.get("state").getAsString().equals("stopped")) {
				return s.get("tick").getAsInt() / 20.0;
			}
		}
		return -1;
	}

	private void renderTimeline(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (timelineW <= 0) {
			return;
		}
		int x0 = timelineX, y0 = timelineY, w = timelineW - 70;
		double len = sceneLength();
		g.fill(x0, y0 + 6, x0 + w, y0 + 20, 0x40FFFFFF);
		// seconds ruler
		int stepS = len > 120 ? 30 : len > 40 ? 10 : len > 12 ? 5 : 1;
		for (int s = 0; s <= len; s += stepS) {
			int tx = x0 + (int) (s / len * w);
			g.fill(tx, y0 + 18, tx + 1, y0 + 22, 0x80FFFFFF);
			g.text(font, s + "s", tx + 2, y0 + 23, 0xFF888888, false);
		}
		JsonArray evs = sceneEvents();
		for (int i = 0; i < evs.size(); i++) {
			double t = i == dragEvent ? dragTime : evs.get(i).getAsJsonObject().get("t").getAsDouble();
			int ex = x0 + (int) (t / len * w);
			boolean hover = Math.abs(mouseX - ex) <= 3 && mouseY >= y0 + 4 && mouseY <= y0 + 22;
			g.fill(ex - 2, y0 + 7, ex + 2, y0 + 19, i == dragEvent ? 0xFFFFFF60 : hover ? 0xFFFFE0A0 : 0xFFFF9030);
			if (hover && dragEvent < 0) {
				String l = (i + 1) + ". " + fmt(t) + " s  " + evs.get(i).getAsJsonObject().get("label").getAsString();
				g.text(font, ClientI18n.t(l), Math.min(ex + 4, x0 + w - font.width(l)), y0 - 4, 0xFFFFE0A0, true);
			}
		}
		// field "Bei" and play head
		try {
			double bt = Double.parseDouble(builderTime);
			int bx = x0 + (int) (Math.min(bt, len) / len * w);
			g.fill(bx, y0 + 4, bx + 1, y0 + 21, 0xFF60C0FF);
		} catch (NumberFormatException ignored) {
			// nothing
		}
		double head = sceneTick();
		if (head >= 0) {
			int hx = x0 + (int) (Math.min(head, len) / len * w);
			g.fill(hx, y0 + 2, hx + 2, y0 + 22, 0xFF7CFF7C);
		}
		if (dragEvent >= 0) {
			g.text(font, fmt(dragTime) + " s", x0 + (int) (dragTime / len * w) + 4, y0 - 4, 0xFFFFFF60, true);
		}
	}

	private void renderPathView(GuiGraphicsExtractor g) {
		if (pathViewW <= 0) {
			return;
		}
		JsonObject p = state.getAsJsonObject("camera_paths").getAsJsonObject(pathSel);
		JsonArray pts = p.getAsJsonArray("points");
		int x0 = pathViewX, y0 = pathViewY, w = pathViewW, h = pathViewH;
		g.fill(x0, y0, x0 + w, y0 + h, 0x40000000);
		g.fill(x0, y0, x0 + w, y0 + 1, 0x40FFFFFF);
		g.text(font, ClientI18n.t("Draufsicht (Norden oben)"), x0 + 3, y0 + 3, 0xFF888888, false);
		if (pts.isEmpty()) {
			return;
		}
		double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
		for (var el : pts) {
			JsonArray a = el.getAsJsonArray();
			minX = Math.min(minX, a.get(0).getAsDouble());
			maxX = Math.max(maxX, a.get(0).getAsDouble());
			minZ = Math.min(minZ, a.get(2).getAsDouble());
			maxZ = Math.max(maxZ, a.get(2).getAsDouble());
		}
		double span = Math.max(4, Math.max(maxX - minX, maxZ - minZ));
		double scale = (Math.min(w, h) - 16) / span;
		double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
		java.util.function.DoubleUnaryOperator px = v -> x0 + w / 2.0 + (v - cx) * scale;
		java.util.function.DoubleUnaryOperator pz = v -> y0 + h / 2.0 + 4 + (v - cz) * scale;
		// line between points
		for (int i = 0; i + 1 < pts.size(); i++) {
			JsonArray a = pts.get(i).getAsJsonArray(), b = pts.get(i + 1).getAsJsonArray();
			for (int k = 0; k <= 20; k++) {
				double t = k / 20.0;
				double vx = a.get(0).getAsDouble() + (b.get(0).getAsDouble() - a.get(0).getAsDouble()) * t;
				double vz = a.get(2).getAsDouble() + (b.get(2).getAsDouble() - a.get(2).getAsDouble()) * t;
				int sx = (int) px.applyAsDouble(vx), sz = (int) pz.applyAsDouble(vz);
				g.fill(sx, sz, sx + 1, sz + 1, 0xFF60A0FF);
			}
		}
		for (int i = 0; i < pts.size(); i++) {
			JsonArray a = pts.get(i).getAsJsonArray();
			int sx = (int) px.applyAsDouble(a.get(0).getAsDouble()), sz = (int) pz.applyAsDouble(a.get(2).getAsDouble());
			g.fill(sx - 2, sz - 2, sx + 3, sz + 3, i == 0 ? 0xFF40FF40 : 0xFFFFC030);
			// view direction
			double yaw = Math.toRadians(a.get(3).getAsDouble());
			for (int k = 2; k <= 7; k++) {
				g.fill(sx + (int) (-Math.sin(yaw) * k), sz + (int) (Math.cos(yaw) * k), sx + (int) (-Math.sin(yaw) * k) + 1, sz + (int) (Math.cos(yaw) * k) + 1, 0xFFAADDFF);
			}
			g.text(font, String.valueOf(i + 1), sx + 3, sz - 9, 0xFFFFFFFF, false);
		}
		// moving dot: where the camera is at this moment of the move (looping)
		double total = 0;
		for (int i = 0; i + 1 < pts.size(); i++) {
			total += pts.get(i).getAsJsonArray().get(4).getAsDouble();
		}
		if (total > 0 && pts.size() > 1) {
			double t = (System.currentTimeMillis() - previewStart) / 1000.0 % total;
			for (int i = 0; i + 1 < pts.size(); i++) {
				double d = pts.get(i).getAsJsonArray().get(4).getAsDouble();
				if (t <= d) {
					JsonArray a = pts.get(i).getAsJsonArray(), b = pts.get(i + 1).getAsJsonArray();
					double k = d <= 0 ? 0 : t / d;
					double vx = a.get(0).getAsDouble() + (b.get(0).getAsDouble() - a.get(0).getAsDouble()) * k;
					double vz = a.get(2).getAsDouble() + (b.get(2).getAsDouble() - a.get(2).getAsDouble()) * k;
					int sx = (int) px.applyAsDouble(vx), sz = (int) pz.applyAsDouble(vz);
					g.fill(sx - 2, sz - 2, sx + 2, sz + 2, 0xFFFF5050);
					g.text(font, fmt(Math.round(t * 10) / 10.0) + " s", x0 + w - 34, y0 + 3, 0xFFFF8080, false);
					break;
				}
				t -= d;
			}
		}
	}

	private boolean inTimeline(double mx, double my) {
		return timelineW > 0 && mx >= timelineX && mx <= timelineX + timelineW - 70 && my >= timelineY + 2 && my <= timelineY + 24;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean doubleClick) {
		if (inTimeline(e.x(), e.y())) {
			double len = sceneLength();
			int w = timelineW - 70;
			JsonArray evs = sceneEvents();
			for (int i = 0; i < evs.size(); i++) {
				int ex = timelineX + (int) (evs.get(i).getAsJsonObject().get("t").getAsDouble() / len * w);
				if (Math.abs(e.x() - ex) <= 3) {
					dragEvent = i;
					dragTime = evs.get(i).getAsJsonObject().get("t").getAsDouble();
					return true;
				}
			}
			builderTime = fmt(Math.max(0, Math.round((e.x() - timelineX) / w * len * 20) / 20.0));
			status = "Zeit „Bei“: " + builderTime + " s";
			statusError = false;
			rebuildWidgets();
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
		if (dragEvent >= 0) {
			double len = sceneLength();
			dragTime = Math.max(0, Math.round((e.x() - timelineX) / (timelineW - 70) * len * 20) / 20.0);
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
		if (dragEvent >= 0) {
			JsonArray evs = sceneEvents();
			if (dragEvent < evs.size()) {
				double old = evs.get(dragEvent).getAsJsonObject().get("t").getAsDouble();
				double delta = Math.round((dragTime - old) * 20) / 20.0;
				if (Math.abs(delta) >= 0.05) {
					perform("btb scene shift " + builderScene + " " + (dragEvent + 1) + " " + fmt(delta), null, true);
				}
			}
			dragEvent = -1;
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		boolean typing = getFocused() instanceof EditBox;
		if (!typing && Minecraft.getInstance().hasControlDown() && e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_Z) {
			perform("btb undo", "↶", true);
			return true;
		}
		if (Minecraft.getInstance().hasControlDown() && e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_F) {
			searchFocus = true;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(e);
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
		int tabW = tabWidth(panelW);
		g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
		g.fill(panelX, panelY, panelX + panelW, panelY + 2, ACCENT);
		g.fill(panelX + tabW + 2, panelY + 30, panelX + tabW + 3, panelY + panelH - 32, 0x40FFFFFF);
		String title = "BEHIND THE BLOCKS — DIRECTOR";
		if (font.width(ClientI18n.c(title).withStyle(TITLE_FONT)) > titleRoom) {
			title = "BTB DIRECTOR";
		}
		if (font.width(ClientI18n.c(title).withStyle(TITLE_FONT)) <= titleRoom + 20) {
			g.text(font, ClientI18n.c(title).withStyle(TITLE_FONT), panelX + 10, panelY + 10, 0xFFFFE0C0, true);
		}
		renderStatusBar(g, panelX + 10, panelY + 25, panelW - 20);
		for (int i = 0; i < fieldLabels.size(); i++) {
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(fieldLabelTexts.get(i)), fieldLabels.get(i)[0], fieldLabels.get(i)[1], 0xFFBBBBBB, false);
		}
		if (clickMode != ClickMode.RUN) { // a coloured frame: clicks do not run right away
			int col = clickMode == ClickMode.ON_CLOSE ? 0xFFFFC060 : 0xFFFF6060;
			g.fill(panelX, panelY, panelX + panelW, panelY + 1, col);
			g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, col);
			g.fill(panelX, panelY, panelX + 1, panelY + panelH, col);
			g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, col);
		}
		for (PlacedRow r : rows) {
			g.fill(r.x - 2, r.y, panelX + panelW - 8, r.y + ROW_H - 2, 0x22FFFFFF);
			String l = r.row.label;
			if (font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(l)) > r.labelW) { // never under the buttons
				l = font.plainSubstrByWidth(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(l), Math.max(10, r.labelW - font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t("…")))) + "…";
			}
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(l), r.x + 4, r.y + 6, r.row.color, false);
		}
		if (pageLabel != null) {
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(pageLabel), pageLabelPos[0], pageLabelPos[1], 0xFFAAAAAA, false);
		}
		if (emptyHint != null) {
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(section == Section.BUILDER ? "Noch keine Schritte – oben hinzufügen oder live aufnehmen" : "Nichts angelegt"), emptyHint[0], emptyHint[1], 0xFF888888, false);
		}
		renderTimeline(g, mouseX, mouseY);
		renderPathView(g);
		// Shift over a button: show its command at the bottom (for those who want to type it)
		String shiftCmd = null;
		if (Minecraft.getInstance().hasShiftDown()) {
			for (var en : buttonCommands.entrySet()) {
				if (en.getKey().isHovered()) {
					shiftCmd = "/" + en.getValue();
					break;
				}
			}
		}
		int room = panelX + panelW - 96 - statusX;
		if (shiftCmd != null && room > 30) {
			String s = font.width(shiftCmd) > room ? font.plainSubstrByWidth(shiftCmd, room - 6) + "…" : shiftCmd;
			g.text(font, s, statusX, panelY + panelH - 21, 0xFF9CE0FF, false);
		} else if (!status.isEmpty()) {
			if (!status.equals(statusShown)) {
				statusShown = status;
				statusAt = System.currentTimeMillis();
			}
			long age = System.currentTimeMillis() - statusAt;
			if (age < 4000) {
				// a short note in the middle: what just happened (or what went wrong)
				String s = ClientI18n.t(status);
				int maxW = panelW - 60;
				if (font.width(s) > maxW) {
					s = font.plainSubstrByWidth(s, maxW - 6) + "…";
				}
				int tw = font.width(s);
				int tx = panelX + panelW / 2 - tw / 2, ty = panelY + panelH - 48;
				int alpha = age > 3200 ? (int) (255 * (4000 - age) / 800.0) : 255;
				g.fill(tx - 6, ty - 4, tx + tw + 6, ty + 12, (Math.min(230, alpha) << 24) | (statusError ? 0x401010 : 0x103018));
				g.fill(tx - 6, ty - 4, tx - 4, ty + 12, (alpha << 24) | (statusError ? 0xFF5050 : 0x60E060));
				g.text(font, s, tx, ty, (alpha << 24) | (statusError ? 0xFF9090 : 0xD0FFD0), false);
			}
			if (room > 30) {
				String s = font.width(ClientI18n.t(status)) > room ? font.plainSubstrByWidth(ClientI18n.t(status), room - font.width("…")) + "…" : ClientI18n.t(status);
				g.text(font, s, statusX, panelY + panelH - 21, statusError ? 0xFFCC7070 : 0xFF888888, false);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private String shownObsStatus;
	private int ticksOpen;

	private boolean anythingRunning() {
		if (objects("scenes").stream().anyMatch(s -> !s.get("state").getAsString().equals("stopped"))) {
			return true;
		}
		JsonObject rp = state != null && state.has("replays") ? state.getAsJsonObject("replays") : new JsonObject();
		return rp.has("recording") && !rp.get("recording").getAsString().isEmpty() || rp.has("playing") && !rp.get("playing").getAsString().isEmpty();
	}

	/** One line under the title: what runs right now. */
	private void renderStatusBar(GuiGraphicsExtractor g, int x, int y, int w) {
		List<String[]> parts = new ArrayList<>(); // text, colour
		if (clickMode == ClickMode.ON_CLOSE) {
			parts.add(new String[] {"🎬 Dreh-Modus" + (PENDING.isEmpty() ? "" : ": " + PENDING.size() + " Aktion(en) beim Schließen"), "FFC060"});
		} else if (clickMode != ClickMode.RUN) {
			parts.add(new String[] {switch (clickMode) {
				case SCENE -> "● Klicks → Szene „" + builderScene + "“ bei " + builderTime + " s";
				case SLOT -> "● Nächster Klick → Numpad " + targetSlot;
				default -> "● Klicks → Cue-Liste";
			}, "FF6060"});
		}
		JsonObject rp = state != null && state.has("replays") ? state.getAsJsonObject("replays") : new JsonObject();
		if (rp.has("recording") && !rp.get("recording").getAsString().isEmpty()) {
			parts.add(new String[] {"● REC Replay " + rp.get("recording").getAsString(), "FF5050"});
		}
		if (rp.has("playing") && !rp.get("playing").getAsString().isEmpty()) {
			parts.add(new String[] {"▶ Replay " + rp.get("playing").getAsString(), "7CFF7C"});
		}
		List<String> running = objects("scenes").stream().filter(s -> !s.get("state").getAsString().equals("stopped")).map(s -> s.get("id").getAsString()).toList();
		if (!running.isEmpty()) {
			parts.add(new String[] {"▶ " + (running.size() == 1 ? "Szene " + running.get(0) : running.size() + " Szenen"), "7CFF7C"});
		}
		if (de.behindtheblocks.cinematic.client.export.ExportManager.active()) {
			parts.add(new String[] {"⏺ Export läuft", "FFC060"});
		}
		int cur = StudioClient.current();
		if (cur >= 0) {
			parts.add(new String[] {"🎬 Shot " + (cur + 1) + "/" + StudioClient.shots().size(), "FFC080"});
		}
		if (parts.isEmpty()) {
			parts.add(new String[] {"Bereit · nichts läuft", "777777"});
		}
		int cx = x;
		for (String[] p : parts) {
			String t = ClientI18n.t(p[0]);
			if (cx + font.width(t) > x + w) {
				break;
			}
			g.text(font, t, cx, y, 0xFF000000 | Integer.parseInt(p[1], 16), false);
			cx += font.width(t) + 12;
		}
	}

	/** Live OBS status: rebuild the Aufnahme tab when OBS answers (connect, test, pause …). */
	@Override
	public void tick() {
		super.tick();
		if (rebuildSoon) {
			rebuildSoon = false;
			rebuildWidgets();
		}
		// keep running states fresh while the menu is open (playheads, "läuft", recordings)
		if (++ticksOpen % 40 == 0 && anythingRunning()) {
			refreshSoon();
		}
		String now = de.behindtheblocks.cinematic.client.obs.ObsBridge.status();
		if (section == Section.RECORDING && shownObsStatus != null && !now.equals(shownObsStatus)) {
			rebuildWidgets();
		}
		shownObsStatus = now;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
