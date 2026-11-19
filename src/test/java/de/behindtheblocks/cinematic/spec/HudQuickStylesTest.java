package de.behindtheblocks.cinematic.spec;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.hud.HudQuickStyles;
import de.behindtheblocks.cinematic.hud.HudTextSpec;
import de.behindtheblocks.cinematic.json.JsonParamException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HudQuickStylesTest {
	@Test
	void everyStyleBuildsAValidText() {
		for (String style : HudQuickStyles.STYLES.keySet()) {
			JsonObject o = HudQuickStyles.build(style, 5, "NAME | Untertitel");
			HudTextSpec.fromJson(style, o); // throws if invalid
		}
	}

	@Test
	void zeroSecondsStays() {
		assertEquals(-1, HudQuickStyles.build("endkarte", 0, "ENDE").get("hold").getAsInt());
	}

	@Test
	void secondsIncludeFades() {
		JsonObject o = HudQuickStyles.build("titel", 5, "A");
		assertEquals(100, o.get("in").getAsInt() + o.get("hold").getAsInt() + o.get("out").getAsInt());
	}

	@Test
	void unknownStyleAndEmptyTextFail() {
		assertThrows(JsonParamException.class, () -> HudQuickStyles.build("gibtsnicht", 5, "A"));
		assertThrows(JsonParamException.class, () -> HudQuickStyles.build("titel", 5, " | "));
	}
}
