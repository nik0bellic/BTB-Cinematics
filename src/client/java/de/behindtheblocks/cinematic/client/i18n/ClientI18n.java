package de.behindtheblocks.cinematic.client.i18n;

import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.util.I18n;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

/**
 * Client texts (director console, replay editor, export, tooltips) in German or English: the server's
 * setting ("auto" / "de" / "en") or, on "auto", the Minecraft language.
 */
public final class ClientI18n {
	private ClientI18n() {
	}

	public static void init() {
		// the server tells its language setting (also on join), so menus and chat always match
		ClientCueDispatcher.register("btb_language", (scene, tick, data) -> I18n.setLanguage(data.get("language").getAsString(), false));
	}

	public static boolean german() {
		Minecraft mc = Minecraft.getInstance();
		return I18n.germanFor(mc.getLanguageManager() == null ? null : mc.getLanguageManager().getSelected());
	}

	public static String t(String text) {
		return I18n.tr(text, german());
	}

	/** Components pass through (already built), only plain strings are translated. */
	public static Component t(Component c) {
		return c;
	}

	public static FormattedCharSequence t(FormattedCharSequence s) {
		return s;
	}

	/** Drop-in for Component.literal(...) in the UI. */
	public static MutableComponent c(String text) {
		return Component.literal(t(text));
	}
}
