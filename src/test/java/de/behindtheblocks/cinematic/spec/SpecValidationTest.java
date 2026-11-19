package de.behindtheblocks.cinematic.spec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.hud.HudTextSpec;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.util.Colors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Validation of the JSON specs that travel from server to client. */
class SpecValidationTest {
	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void colors() {
		assertEquals(0xFFFFFFFF, Colors.parse("white"));
		assertEquals(0xFF112233, Colors.parse("#112233"));
		assertEquals(0x80112233, Colors.parse("#80112233"));
		assertThrows(JsonParamException.class, () -> Colors.parse("nope"));
		assertEquals(0x80FFFFFF, Colors.withAlpha(0xFFFFFFFF, 128 / 255f));
	}

	@Test
	void effectWireRoundTrip() {
		EffectSpec fade = EffectSpec.fromJson(EffectSpec.Kind.FADE, new JsonParams(json("{\"color\":\"white\",\"duration\":60}")));
		assertEquals(60, fade.in());
		assertEquals(-1, fade.hold());
		EffectSpec back = EffectSpec.fromWire(fade.toJson());
		assertEquals(fade, back);
	}

	@Test
	void effectRangesAreChecked() {
		assertThrows(JsonParamException.class, () -> EffectSpec.fromJson(EffectSpec.Kind.BLUR, new JsonParams(json("{\"intensity\":50}"))));
		assertThrows(JsonParamException.class, () -> EffectSpec.fromJson(EffectSpec.Kind.TRANSITION, new JsonParams(json("{\"style\":\"spin\"}"))));
	}

	@Test
	void hudSpecDefaultsAndStyles() {
		HudTextSpec s = HudTextSpec.fromJson("x", json("{\"text\":\"A\\nB\",\"style\":\"lower_third\"}"));
		assertEquals(2, s.lines().size());
		assertEquals(HudTextSpec.Kind.LOWER_THIRD, s.style());
		assertEquals(HudTextSpec.AnimIn.FADE, s.animIn());
		assertThrows(JsonParamException.class, () -> HudTextSpec.fromJson("x", json("{\"text\":\"A\",\"scale\":99}")));
		assertThrows(JsonParamException.class, () -> HudTextSpec.fromJson("x", json("{\"scale\":2}")));
	}
}
