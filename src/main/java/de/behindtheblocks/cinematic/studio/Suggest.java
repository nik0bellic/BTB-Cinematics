package de.behindtheblocks.cinematic.studio;

import java.util.Collection;
import java.util.Locale;

/** "Did you mean …?" for mistyped names and command words. */
public final class Suggest {
	private Suggest() {
	}

	/** The closest known word, or null when nothing is reasonably close. */
	public static String closest(String typed, Collection<String> known) {
		if (typed == null || known == null || known.isEmpty()) {
			return null;
		}
		String t = typed.toLowerCase(Locale.ROOT);
		String best = null;
		int bestD = Integer.MAX_VALUE;
		for (String k : known) {
			String kl = k.toLowerCase(Locale.ROOT);
			int d = kl.startsWith(t) || t.startsWith(kl) ? Math.abs(kl.length() - t.length()) / 2 : distance(t, kl);
			if (d < bestD) {
				bestD = d;
				best = k;
			}
		}
		int limit = Math.max(2, t.length() / 3);
		return bestD <= limit ? best : null;
	}

	/** " – meintest du „x“?" or "". */
	public static String hint(String typed, Collection<String> known) {
		String c = closest(typed, known);
		return c == null ? "" : " – meintest du „" + c + "“?";
	}

	static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
