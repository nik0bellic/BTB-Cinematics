package de.behindtheblocks.cinematic.studio;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ready-made shots. Each template becomes a normal scene ("vorlage_<name>") built from ordinary
 * /btb commands, so it can be played at once or opened in the builder and changed.
 */
public final class Templates {
	/** steps: {seconds, command without slash} */
	public interface Builder {
		List<String[]> build(Entity actor, Vec3 actorPos, Entity other, CommandSourceStack src, String cams);
	}

	public record Template(String id, String title, String description, Builder builder) {
		public List<String[]> steps(Entity actor, CommandSourceStack src) {
			// who watches: the players with role "kamera"; nobody tagged → everyone (see CommandUtil.targets)
			String cams = "@a[tag=kamera]";
			Entity other = null;
			double best = 40 * 40;
			for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
				if (p != actor && !p.entityTags().contains("kamera") && !p.isSpectator() && p.distanceToSqr(actor) < best) {
					best = p.distanceToSqr(actor);
					other = p;
				}
			}
			return builder.build(actor, actor.position(), other, src, cams);
		}
	}

	private static final Map<String, Template> ALL = new LinkedHashMap<>();

	private static String sel(Entity e) {
		return e instanceof ServerPlayer p ? p.getName().getString() : e.getStringUUID();
	}

	private static String pos(Vec3 v) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
	}

	private static String[] s(double t, String cmd) {
		return new String[] {String.format(Locale.ROOT, "%.2f", t), cmd};
	}

	static {
		add("establishing", "Establishing Shot", "Weiter Kreis hoch über dem Ort, Kinobalken, Auf- und Abblende (12 s)", (a, p, o, src, cams) -> List.of(
				s(0, "effects fade black 0.01"),
				s(0, "effects letterbox 0.12 1"),
				s(0, "camera orbit " + pos(p) + " 22 12 12 200 " + cams),
				s(0.2, "effects fadeout 2"),
				s(10.5, "effects fade black 1.5"),
				s(12, "camera stop " + cams),
				s(12.1, "effects clear 1")));
		add("verfolgung", "Verfolgungsjagd", "Kamera hinter dem Darsteller, wackelige Handkamera, Zoom-Ruck (10 s)", (a, p, o, src, cams) -> List.of(
				s(0, "camera follow " + sel(a) + " behind 5 2 +z " + cams),
				s(0, "camera handheld 1.8 " + cams),
				s(0, "effects letterbox 0.1 0.5"),
				s(4, "camera follow " + sel(a) + " low 3 0.4 +z " + cams),
				s(7, "camera follow " + sel(a) + " side 7 1.5 +z " + cams),
				s(10, "camera handheld off " + cams),
				s(10, "camera stop " + cams),
				s(10, "effects clear 0.6")));
		add("dialog", "Schuss-Gegenschuss", "Über-die-Schulter abwechselnd auf zwei Darsteller (16 s)", (a, p, o, src, cams) -> {
			Entity b = o != null ? o : a;
			List<String[]> l = new ArrayList<>();
			l.add(s(0, "effects letterbox 0.12 1"));
			for (int i = 0; i < 4; i++) {
				Entity speaker = i % 2 == 0 ? a : b, listener = i % 2 == 0 ? b : a;
				// shoulder of the listener, looking at the speaker
				l.add(s(i * 4, "camera follow " + sel(listener) + " shoulder 2.5 0.6 +z " + cams));
			}
			l.add(s(16, "camera stop " + cams));
			l.add(s(16, "effects letterbox off 1"));
			return l;
		});
		add("enthuellung", "Enthüllung", "Aus Schwarz, Kamera steigt hinter dem Darsteller auf, dumpfer Ton, Blitz (10 s)", (a, p, o, src, cams) -> List.of(
				s(0, "effects fade black 0.01"),
				s(0, "sound volume 0.3"),
				s(0, "camera follow " + sel(a) + " low 4 0.3 +z " + cams),
				s(0.5, "effects fadeout 2.5"),
				s(4, "camera follow " + sel(a) + " crane 12 7 +z " + cams),
				s(4, "sound volume 1"),
				s(6, "effects flash white"),
				s(10, "camera stop " + cams),
				s(10, "effects clear 1")));
		add("heldenshot", "Helden-Shot", "Untersicht, langsamer Kreis, Zeitlupe (8 s)", (a, p, o, src, cams) -> List.of(
				s(0, "effects letterbox 0.12 0.8"),
				s(0, "camera orbit entity " + sel(a) + " 4 0.2 8 120 " + cams),
				s(0.5, "slowmo 0.5 4"),
				s(8, "camera stop " + cams),
				s(8, "effects clear 0.8")));
		add("ueberflug", "Überflug", "Hoch über dem Darsteller, Blick nach unten, Kamera zieht weg (8 s)", (a, p, o, src, cams) -> List.of(
				s(0, "camera follow " + sel(a) + " top 6 10 +z " + cams),
				s(4, "camera follow " + sel(a) + " top 16 22 +z " + cams),
				s(8, "camera stop " + cams)));
		add("auto", "Auto-Regie", "Wechselt alle 5 s die Perspektive auf den Darsteller (30 s)", (a, p, o, src, cams) -> List.of(
				s(0, "camera auto " + sel(a) + " 5 " + cams),
				s(30, "camera stop " + cams)));
	}

	private static void add(String id, String title, String description, Builder b) {
		ALL.put(id, new Template(id, title, description, b));
	}

	public static List<String> ids() {
		return List.copyOf(ALL.keySet());
	}

	public static Template get(String id) {
		return ALL.get(id.toLowerCase(Locale.ROOT));
	}

	public static List<Template> all() {
		return List.copyOf(ALL.values());
	}
}
