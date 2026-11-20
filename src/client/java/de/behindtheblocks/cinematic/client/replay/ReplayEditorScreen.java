package de.behindtheblocks.cinematic.client.replay;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.client.replay.ReplayEditor.Keyframe;
import de.behindtheblocks.cinematic.client.replay.ReplayEditor.Mode;
import de.behindtheblocks.cinematic.replay.ReplayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.Locale;

/**
 * The replay editor panel: transport, a zoomable timeline (actors, events, camera keyframes, in/out),
 * keyframe settings and the export bar. The upper part of the screen stays free – the shot is visible.
 */
public final class ReplayEditorScreen extends Screen {
	private static final int PANEL_H = 112;
	private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f};

	private int y0, tlX, tlY, tlW, tlH;
	private double viewStart, viewEnd = -1;
	private boolean dragHead, dragKey;
	private double ghost = -1;
	private EditBox nameBox;
	private String lastState = "-";

	public ReplayEditorScreen() {
		super(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("Replay-Editor"));
	}

	@Override
	protected void init() {
		y0 = height - PANEL_H;
		tlX = 8;
		tlW = width - 16;
		tlY = y0 + 26;
		tlH = 36;
		if (viewEnd < 0) {
			viewEnd = ReplayEditor.length();
		}
		// row 1: transport + take
		int x = 8, y = y0 + 6;
		x = add("⏮", 18, x, y, "Zum Anfang (bzw. In-Punkt)", () -> ReplayEditor.seek(ReplayEditor.time(0) > ReplayEditor.in + 0.1 ? ReplayEditor.in : 0));
		x = add("−5", 20, x, y, "5 s zurück (←: 1 s)", () -> ReplayEditor.seek(ReplayEditor.time(0) - 5));
		x = add(playing() ? "⏸" : "▶", 22, x, y, "Abspielen / Pause (Leertaste, im Flug: ↑)", ReplayEditor::togglePlay);
		x = add("+5", 20, x, y, "5 s vor (→: 1 s)", () -> ReplayEditor.seek(ReplayEditor.time(0) + 5));
		x = add("⏭", 18, x, y, "Zum Ende (bzw. Out-Punkt)", () -> ReplayEditor.seek(ReplayEditor.outPoint()));
		float sp = ReplayManager.currentSpeed();
		x = add("Tempo " + fmtSpeed(sp), 52, x + 4, y, "Abspieltempo der ganzen Welt (Zeitlupe/Zeitraffer)", () -> {
			int i = 0;
			while (i < SPEEDS.length && SPEEDS[i] <= sp + 0.01f) {
				i++;
			}
			ReplayEditor.setSpeed(SPEEDS[i % SPEEDS.length]);
		});
		int rx = width - 8;
		rx = addLeft("Beenden", 46, rx, y, "Editor schließen, Replay stoppen, Darsteller zurück", ReplayEditor::close);
		rx = addLeft("✈ Fliegen", 50, rx - 4, y, "Panel zu, frei fliegen und Blickwinkel suchen (K setzt Keyframe, F6 zurück)", this::onClose);
		rx = addLeft("▶", 14, rx - 4, y, "Nächstes Replay", () -> {
			ReplayEditor.switchTake(1);
			viewStart = 0;
			viewEnd = ReplayEditor.length();
			rebuildWidgets();
		});
		takeLabelRight = rx - 2;
		addLeft("◀", 14, rx - 4 - Math.min(90, font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(ReplayEditor.name)) + 4), y, "Vorheriges Replay", () -> {
			ReplayEditor.switchTake(-1);
			viewStart = 0;
			viewEnd = ReplayEditor.length();
			rebuildWidgets();
		});

		// row 3: keyframes
		x = 8;
		y = y0 + 68;
		x = add("+ Keyframe", 62, x, y, "Keyframe mit der aktuellen Sicht an der Abspielposition (im Flug: K)", () -> {
			ReplayEditor.addKeyframe();
			rebuildWidgets();
		});
		x = add(ReplayEditor.cameraOn ? "◆ Keyframes" : "✈ Frei", 66, x, y,
				"Keyframes: die Kamera fährt den Pfad ab. Frei: du fliegst selbst (Taste H)", () -> {
					ReplayEditor.cameraOn = !ReplayEditor.cameraOn;
					rebuildWidgets();
				});
		Keyframe sel = selected();
		if (sel != null) {
			x = add(sel.mode.label, 58, x + 4, y, "Wie die Kamera von hier zum nächsten Keyframe kommt: Fahrt (weich), Schnitt (harter Cut), "
					+ "Fahrt+Blick (schaut auf den nächsten Darsteller), Frei (keine Steuerung)", () -> {
						sel.mode = sel.mode.next();
						ReplayEditor.save();
						rebuildWidgets();
					});
			x = add("FOV−", 28, x, y, "Brennweite: enger (Tele)", () -> fov(sel, -5));
			x = add("FOV+", 28, x, y, "Brennweite: weiter (Weitwinkel)", () -> fov(sel, 5));
			x = add("↺", 16, x, y, "Kamera neigen (Roll) links", () -> roll(sel, -5));
			x = add("↻", 16, x, y, "Kamera neigen (Roll) rechts", () -> roll(sel, 5));
			x = add("Sicht", 34, x, y, "Aktuelle Sicht (und Zeit) in diesen Keyframe übernehmen", () -> {
				ReplayEditor.takeView(sel);
				ReplayEditor.save();
			});
			x = add("Hin", 26, x, y, "Zu diesem Keyframe springen und dich dorthin stellen", () -> {
				ReplayEditor.seek(sel.t);
				ReplayEditor.cmd(String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", sel.x, sel.y - 1.62, sel.z, sel.yaw, sel.pitch));
			});
			x = add("✕", 16, x, y, "Keyframe löschen (Entf)", this::deleteSelected);
		}
		// multicam: edit camera A–D, cut to it at the play head
		int mx = width - 8;
		mx = addLeft("Schnitte ✕", 58, mx, y, "Alle Schnitte löschen (dann zeigt der Film die Kamera, die du gerade bearbeitest)", () -> {
			ReplayEditor.CUTS.clear();
			ReplayEditor.save();
			rebuildWidgets();
		});
		mx = addLeft("✂ → " + ReplayEditor.CAM_NAMES[ReplayEditor.activeCam], 44, mx - 2, y,
				"Schnitt: ab der Abspielposition zeigt der Film Kamera " + ReplayEditor.CAM_NAMES[ReplayEditor.activeCam], () -> {
					ReplayEditor.cutHere(ReplayEditor.time(0));
					rebuildWidgets();
				});
		multicamLeft = mx - 2 - 56;
		addLeft("Kamera " + ReplayEditor.CAM_NAMES[ReplayEditor.activeCam] + " ▸", 56, mx - 2, y,
				"Mehrere Kameras: jede hat eigene Keyframes. Klicken = nächste Kamera bearbeiten (A–D)", () -> {
					ReplayEditor.switchCam((ReplayEditor.activeCam + 1) % ReplayEditor.CAM_NAMES.length);
					rebuildWidgets();
				});

		// row 4: export
		x = 8;
		y = y0 + 90;
		nameBox = new EditBox(font, x, y, 70, 16, de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("Name"));
		nameBox.setMaxLength(40);
		nameBox.setValue(ReplayEditor.exportName);
		nameBox.setResponder(v -> ReplayEditor.exportName = v);
		nameBox.setTooltip(Tooltip.create(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c("Name der Datei: .minecraft/btb_export/<Name>/<Name>.mp4")));
		addRenderableWidget(nameBox);
		x += 74;
		x = add(ReplayEditor.RESOLUTIONS[ReplayEditor.res].toUpperCase(Locale.ROOT).replace("P", "p"), 40, x, y, "Auflösung", () -> {
			ReplayEditor.res = (ReplayEditor.res + 1) % ReplayEditor.RESOLUTIONS.length;
			ReplayEditor.save();
			rebuildWidgets();
		});
		x = add(ReplayEditor.FPS[ReplayEditor.fps] + " FPS", 44, x, y, "Bilder pro Sekunde", () -> {
			ReplayEditor.fps = (ReplayEditor.fps + 1) % ReplayEditor.FPS.length;
			ReplayEditor.save();
			rebuildWidgets();
		});
		x = add("[ In", 30, x + 4, y, "Export-Start = Abspielposition (Taste I)", () -> setIn(ReplayEditor.time(0)));
		x = add("Out ]", 32, x, y, "Export-Ende = Abspielposition (Taste P)", () -> setOut(ReplayEditor.time(0)));
		x = add("● Exportieren", 72, x + 4, y, "Rendert den Bereich In→Out bildgenau mit der Keyframe-Kamera (Spiel friert dabei ein)", ReplayEditor::export);
		exportInfoX = x + 6;
	}

	private int takeLabelRight, exportInfoX, multicamLeft;

	private boolean playing() {
		return ReplayEditor.name.equals(ReplayManager.current()) && !ReplayManager.isPaused();
	}

	private static String fmtSpeed(float s) {
		return s == 0.25f ? "¼×" : s == 0.5f ? "½×" : ((int) s) + "×";
	}

	private Keyframe selected() {
		return ReplayEditor.selected >= 0 && ReplayEditor.selected < ReplayEditor.KEYS.size() ? ReplayEditor.KEYS.get(ReplayEditor.selected) : null;
	}

	private void fov(Keyframe k, float d) {
		k.fov = Math.max(10, Math.min(130, (k.fov <= 0 ? 70 : k.fov) + d));
		ReplayEditor.save();
	}

	private void roll(Keyframe k, float d) {
		k.roll = Math.max(-45, Math.min(45, k.roll + d));
		ReplayEditor.save();
	}

	private void setIn(double t) {
		ReplayEditor.in = Math.min(t, ReplayEditor.outPoint() - 0.1);
		ReplayEditor.save();
	}

	private void setOut(double t) {
		ReplayEditor.out = Math.max(t, ReplayEditor.in + 0.1);
		ReplayEditor.save();
	}

	private void deleteSelected() {
		if (selected() != null) {
			ReplayEditor.KEYS.remove(ReplayEditor.selected);
			ReplayEditor.selected = -1;
			ReplayEditor.save();
			rebuildWidgets();
		}
	}

	private int add(String label, int w, int x, int y, String tip, Runnable r) {
		Button b = Button.builder(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(label), btn -> r.run()).bounds(x, y, w, 16).build();
		b.setTooltip(Tooltip.create(de.behindtheblocks.cinematic.client.i18n.ClientI18n.c(tip)));
		addRenderableWidget(b);
		return x + w + 2;
	}

	private int addLeft(String label, int w, int right, int y, String tip, Runnable r) {
		add(label, w, right - w, y, tip, r);
		return right - w - 2;
	}

	// ------------------------------------------------------------------ timeline mapping

	private double span() {
		return Math.max(0.5, viewEnd - viewStart);
	}

	private int toX(double t) {
		return tlX + (int) Math.round((t - viewStart) / span() * tlW);
	}

	private double toT(double x) {
		return Math.max(0, Math.min(ReplayEditor.length(), viewStart + (x - tlX) / tlW * span()));
	}

	private boolean inTimeline(double x, double y) {
		return x >= tlX && x <= tlX + tlW && y >= tlY && y <= tlY + tlH;
	}

	private int keyAt(double mx, double my) {
		if (my < tlY + tlH - 12 || my > tlY + tlH) {
			return -1;
		}
		int best = -1;
		double bd = 5;
		for (int i = 0; i < ReplayEditor.KEYS.size(); i++) {
			double d = Math.abs(toX(ReplayEditor.KEYS.get(i).t) - mx);
			if (d <= bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (inTimeline(e.x(), e.y())) {
			int k = keyAt(e.x(), e.y());
			if (k >= 0) {
				if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
					ReplayEditor.selected = k;
					deleteSelected();
					return true;
				}
				ReplayEditor.selected = k;
				dragKey = true;
				if (doubleClick) {
					ReplayEditor.seek(ReplayEditor.KEYS.get(k).t);
				}
				rebuildWidgets();
				return true;
			}
			if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
				// right click on empty timeline: set in (left half of the view) / out (right half)
				double t = toT(e.x());
				if (e.x() < tlX + tlW / 2.0) {
					setIn(t);
				} else {
					setOut(t);
				}
				return true;
			}
			dragHead = true;
			ghost = toT(e.x());
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragHead) {
			ghost = toT(e.x());
			return true;
		}
		if (dragKey && selected() != null) {
			selected().t = Math.round(toT(e.x()) * 20) / 20.0;
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragHead) {
			dragHead = false;
			ReplayEditor.seek(ghost);
			return true;
		}
		if (dragKey) {
			dragKey = false;
			ReplayEditor.sort();
			ReplayEditor.save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (inTimeline(mx, my) && sy != 0) {
			// zoom around the mouse
			double len = ReplayEditor.length(), at = toT(mx), f = sy > 0 ? 0.8 : 1.25;
			double s = Math.max(1, Math.min(len, span() * f));
			viewStart = Math.max(0, Math.min(len - s, at - (at - viewStart) * s / span()));
			viewEnd = viewStart + s;
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (getFocused() instanceof EditBox && e.key() != InputConstants.KEY_ESCAPE) {
			return super.keyPressed(e);
		}
		switch (e.key()) {
			case InputConstants.KEY_SPACE -> ReplayEditor.togglePlay();
			case InputConstants.KEY_LEFT -> ReplayEditor.seek(ReplayEditor.time(0) - 1);
			case InputConstants.KEY_RIGHT -> ReplayEditor.seek(ReplayEditor.time(0) + 1);
			case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> deleteSelected();
			case InputConstants.KEY_K -> {
				ReplayEditor.addKeyframe();
				rebuildWidgets();
			}
			case InputConstants.KEY_I -> setIn(ReplayEditor.time(0));
			case InputConstants.KEY_P -> setOut(ReplayEditor.time(0));
			case InputConstants.KEY_J -> {
				ReplayEditor.cameraOn = !ReplayEditor.cameraOn;
				rebuildWidgets();
			}
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		// play/pause or speed changed (also by hotkeys / chat): relabel the buttons
		String state = playing() + "|" + ReplayManager.currentSpeed() + "|" + ReplayEditor.name;
		if (!state.equals(lastState)) {
			rebuildWidgets();
		}
		lastState = state;
		if (ReplayEditor.name == null) {
			onClose();
		}
	}

	@Override
	public void onClose() {
		ReplayEditor.save();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// the shot stays visible above the panel
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (ReplayEditor.name == null) {
			return;
		}
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double len = ReplayEditor.length(), now = ReplayEditor.time(partial);
		g.fill(0, y0, width, height, 0xD0101014);
		g.fill(0, y0, width, y0 + 1, 0xFFFF7A30);
		// take name + time
		String take = ReplayEditor.name;
		int nameW = Math.min(90, font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(take)));
		g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(font.plainSubstrByWidth(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(take), nameW)), takeLabelRight - nameW, y0 + 10, 0xFFFFE0C0, false);
		String clock = ReplayEditor.clock(now) + " / " + ReplayEditor.clock(len);
		g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(clock), 8 + 18 + 20 + 22 + 20 + 18 + 10 + 4 + 52 + 8, y0 + 10, 0xFFFFFFFF, false);

		// timeline
		g.fill(tlX, tlY, tlX + tlW, tlY + tlH, 0xFF1C1C22);
		int inX = toX(ReplayEditor.in), outX = toX(ReplayEditor.outPoint());
		g.fill(Math.max(tlX, inX), tlY, Math.min(tlX + tlW, outX), tlY + tlH, 0x302060FF);
		clipLine(g, inX, 0xFF6090FF);
		clipLine(g, outX, 0xFF6090FF);
		// ruler
		double step = niceStep(span());
		for (double t = Math.ceil(viewStart / step) * step; t <= viewEnd + 1e-6; t += step) {
			int x = toX(t);
			g.fill(x, tlY, x + 1, tlY + 3, 0xFF707070);
			if (x + 2 + font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t("0:00")) < tlX + tlW) {
				g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(ReplayEditor.clock(t).replace(".0", "")), x + 2, tlY + 2, 0xFF808080, false);
			}
		}
		// actor / creature tracks
		JsonObject h = ReplayEditor.header;
		if (h != null) {
			int lane = 0;
			for (var el : h.getAsJsonArray("tracks")) {
				JsonObject t = el.getAsJsonObject();
				if (lane >= 4) {
					break;
				}
				double s = t.get("start").getAsInt() / 20.0, e = s + t.get("frames").getAsInt() / 20.0;
				int ly = tlY + 12 + lane * 4;
				int col = t.get("kind").getAsString().equals("player") ? 0xFF40B060 : 0xFF907050;
				g.fill(Math.max(tlX, toX(s)), ly, Math.min(tlX + tlW, toX(e)), ly + 2, col);
				lane++;
			}
			// events
			for (var el : h.getAsJsonArray("events")) {
				JsonObject ev = el.getAsJsonObject();
				int x = toX(ev.get("t").getAsInt() / 20.0);
				if (x < tlX || x > tlX + tlW) {
					continue;
				}
				int col = switch (ev.get("type").getAsString()) {
					case "scene_start" -> 0xFF60FF60;
					case "scene_stop" -> 0xFF308030;
					case "destruction" -> 0xFFFF9030;
					case "entity" -> 0xFFFF4040;
					default -> 0xFF909090;
				};
				g.fill(x, tlY + 28, x + 1, tlY + tlH, col);
			}
		}
		// multicam cuts: a coloured band per camera along the top of the timeline
		if (!ReplayEditor.CUTS.isEmpty()) {
			int[] camCol = {0xFFFFB040, 0xFF40C0FF, 0xFFC070FF, 0xFF70E070};
			for (int ci = 0; ci < ReplayEditor.CUTS.size(); ci++) {
				ReplayEditor.Cut cut = ReplayEditor.CUTS.get(ci);
				double end = ci + 1 < ReplayEditor.CUTS.size() ? ReplayEditor.CUTS.get(ci + 1).t() : ReplayEditor.length();
				double start = ci == 0 ? 0 : cut.t();
				int ax = Math.max(tlX, toX(start)), bx = Math.min(tlX + tlW, toX(end));
				if (bx > ax) {
					g.fill(ax, tlY + 1, bx, tlY + 4, camCol[cut.cam() % camCol.length]);
					g.text(font, ReplayEditor.CAM_NAMES[cut.cam()], ax + 2, tlY + 4, camCol[cut.cam() % camCol.length], false);
				}
				int cx = toX(cut.t());
				if (cx >= tlX && cx <= tlX + tlW) {
					g.fill(cx, tlY, cx + 1, tlY + tlH, 0xC0FFFFFF);
				}
			}
		}
		// camera keyframes + their path segments
		int ky = tlY + tlH - 7;
		for (int i = 0; i < ReplayEditor.KEYS.size(); i++) {
			Keyframe k = ReplayEditor.KEYS.get(i);
			int x = toX(k.t);
			if (i + 1 < ReplayEditor.KEYS.size() && k.mode != Mode.FREE) {
				int nx = toX(ReplayEditor.KEYS.get(i + 1).t);
				g.fill(Math.max(tlX, x), ky, Math.min(tlX + tlW, nx), ky + 1, (k.mode.color & 0x00FFFFFF) | 0x90000000);
			}
			if (x < tlX - 3 || x > tlX + tlW + 3) {
				continue;
			}
			boolean sel = i == ReplayEditor.selected;
			int c = sel ? 0xFFFFFFFF : k.mode.color;
			// diamond
			g.fill(x - 1, ky - 3, x + 2, ky + 4, c);
			g.fill(x - 2, ky - 2, x + 3, ky + 3, c);
			g.fill(x - 3, ky - 1, x + 4, ky + 2, c);
		}
		// playhead (+ ghost while scrubbing)
		if (ghost >= 0 && dragHead) {
			clipLine(g, toX(ghost), 0x90FFFFFF);
		}
		int px = toX(now);
		if (px >= tlX && px <= tlX + tlW) {
			g.fill(px, tlY - 2, px + 1, tlY + tlH + 2, 0xFFFFFFFF);
			g.fill(px - 2, tlY - 3, px + 3, tlY - 1, 0xFFFFFFFF);
		}
		g.outline(tlX - 1, tlY - 1, tlW + 2, tlH + 2, 0xFF404048);

		// keyframe info
		Keyframe sel = selected();
		String info = sel == null ? (ReplayEditor.KEYS.isEmpty() ? "Keine Keyframes – fliegen (✈), Blickwinkel suchen, K drücken" : ReplayEditor.KEYS.size() + " Keyframes · klicken zum Auswählen, ziehen zum Verschieben, Rechtsklick löscht")
				: String.format(Locale.ROOT, "◆ %d/%d · %s · FOV %.0f · Roll %.0f°", ReplayEditor.selected + 1, ReplayEditor.KEYS.size(), ReplayEditor.clock(sel.t), sel.fov, sel.roll);
		// never under the camera A–D buttons on the right
		int infoRight = multicamLeft > 0 ? multicamLeft - 6 : width - 8;
		int infoX = sel == null ? 8 + 62 + 2 + 66 + 8 : infoRight - font.width(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(info));
		if (sel == null || infoX > 8 + 64 + 68 + 6 + 58 + 30 + 30 + 18 + 18 + 36 + 28 + 18) {
			String it = de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(info);
			int room = infoRight - infoX;
			if (room > 20) {
				g.text(font, font.width(it) > room ? font.plainSubstrByWidth(it, room - font.width("…")) + "…" : it, infoX, y0 + 72, 0xFFBBBBBB, false);
			}
		}
		// export info
		double dur = ReplayEditor.outPoint() - ReplayEditor.in;
		String ex = String.format(Locale.ROOT, "%s → %s (%.1f s, %d Bilder)", ReplayEditor.clock(ReplayEditor.in), ReplayEditor.clock(ReplayEditor.outPoint()),
				dur, (int) Math.round(dur * ReplayEditor.FPS[ReplayEditor.fps]));
		if (exportInfoX + 20 < width) {
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(font.plainSubstrByWidth(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(ex), width - exportInfoX - 8)), exportInfoX, y0 + 94, 0xFF8AA8FF, false);
		}
		// help line above the panel
		String help = "Leertaste Play/Pause · ←/→ 1 s · K Keyframe · H Kamera · I/P In/Out · Mausrad: Zoom · Rechtsklick: In/Out bzw. Keyframe löschen";
		String st = ReplayEditor.currentStatus();
		if (st != null) {
			g.fill(0, y0 - 14, width, y0, ReplayEditor.statusError ? 0xC0400000 : 0xA0003000);
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(font.plainSubstrByWidth(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t((ReplayEditor.statusError ? "⚠ " : "✔ ") + st), width - 16)), 8, y0 - 11,
					ReplayEditor.statusError ? 0xFFFFB0B0 : 0xFFB0FFB0, true);
		} else {
			g.text(font, de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(font.plainSubstrByWidth(de.behindtheblocks.cinematic.client.i18n.ClientI18n.t(help), width - 16)), 8, y0 - 11, 0xFFCCCCCC, true);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void clipLine(GuiGraphicsExtractor g, int x, int color) {
		if (x >= tlX && x <= tlX + tlW) {
			g.fill(x, tlY, x + 1, tlY + tlH, color);
		}
	}

	private static double niceStep(double span) {
		double[] steps = {0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300};
		for (double s : steps) {
			if (span / s <= 10) {
				return s;
			}
		}
		return 600;
	}
}
