package de.behindtheblocks.cinematic.client.effects;

import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.client.ClientSceneState;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side screen effects. One active instance per slot (fade, vignette, tint, blur,
 * letterbox, transition) — a new effect in a slot blends from the previous one — plus any number
 * of overlapping flashes. All timing runs on {@code ClientTimeline}, so effects freeze with their
 * scene.
 */
public final class ScreenEffectRenderer {
	private static final Identifier VIGNETTE = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "textures/gui/vignette.png");

	private static final class Active {
		final EffectSpec spec;
		final Envelope env;
		final int fromColor;
		final String sceneId;
		/** Wall-clock time after which a "stays forever" effect of a finished scene fades back (0 = never). */
		long autoReleaseAt;

		Active(String sceneId, EffectSpec spec, Envelope env, int fromColor) {
			this.sceneId = sceneId;
			this.spec = spec;
			this.env = env;
			this.fromColor = fromColor;
		}
	}

	private static final Map<EffectSpec.Kind, Active> SLOTS = new EnumMap<>(EffectSpec.Kind.class);
	private static final List<Active> FLASHES = new ArrayList<>();

	private ScreenEffectRenderer() {
	}

	public static void init() {
		ClientCueDispatcher.register("effect", (scene, tick, data) -> apply(scene, EffectSpec.fromWire(data)));
		// Stopped by hand: the scene's effects (letterbox, vignette, fade …) are released smoothly.
		ClientSceneState.onSceneStopped(sceneId -> {
			for (Active a : SLOTS.values()) {
				if (a.sceneId.equals(sceneId)) {
					a.env.release(15, 0f);
				}
			}
		});
		ClientSceneState.onSceneEnded((sceneId, reset) -> {
			if (!reset) {
				// A scene that ends on black (or white) must not trap the player: after 5 s the picture comes back.
				// Long enough for the recording's black tail; a manual stop already released everything.
				long at = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() + 5000;
				for (Active a : SLOTS.values()) {
					if (!a.sceneId.equals(sceneId) || a.spec.hold() >= 0) {
						continue;
					}
					boolean cover = a.spec.kind() == EffectSpec.Kind.FADE || a.spec.kind() == EffectSpec.Kind.TRANSITION;
					if (cover) {
						a.autoReleaseAt = at; // end-on-black: stays 5 s for the recording, then the picture returns
					} else {
						a.env.release(20, 0f); // letterbox, vignette, blur, tint … end with the scene
					}
				}
			}
			if (reset) {
				SLOTS.values().removeIf(a -> a.sceneId.equals(sceneId));
				FLASHES.removeIf(a -> a.sceneId.equals(sceneId));
			}
		});
	}

	static void apply(String sceneId, EffectSpec spec) {
		float partial = 0f;
		switch (spec.kind()) {
			case FLASH -> FLASHES.add(new Active(sceneId, spec, new Envelope(sceneId, 0, spec.in(), spec.hold(), spec.out()), spec.color()));
			case CLEAR -> {
				for (Map.Entry<EffectSpec.Kind, Active> e : SLOTS.entrySet()) {
					if (spec.style().equals("all") || spec.style().equals(e.getKey().id())) {
						e.getValue().env.release(spec.in(), partial);
					}
				}
			}
			default -> {
				Active previous = SLOTS.get(spec.kind());
				float from = 0;
				int fromColor = spec.color();
				if (previous != null && spec.kind() != EffectSpec.Kind.TRANSITION) {
					from = previous.env.value(partial) * previous.spec.intensity() / Math.max(0.0001f, spec.intensity());
					from = Math.min(1, from);
					fromColor = currentColor(previous, partial);
				}
				SLOTS.put(spec.kind(), new Active(sceneId, spec, new Envelope(sceneId, from, spec.in(), spec.hold(), spec.out()), fromColor));
			}
		}
	}

	private static int currentColor(Active a, float partial) {
		int target = a.spec.kind() == EffectSpec.Kind.TINT ? a.spec.color2() : a.spec.color();
		return Colors.lerp(a.fromColor, target, a.env.attackProgress(partial));
	}

	/** Removes finished effects. Called every client tick. */
	public static void tick() {
		SLOTS.values().removeIf(a -> a.env.done(0f));
		FLASHES.removeIf(a -> a.env.done(0f));
		de.behindtheblocks.cinematic.client.sound.CinematicReverb.setMuffle(1f - 0.97f * level(EffectSpec.Kind.MUFFLE, 0f));
	}

	/** Current strength 0..1 of a slot effect (intensity × envelope). */
	private static float level(EffectSpec.Kind kind, float partial) {
		Active a = SLOTS.get(kind);
		return a == null ? 0f : a.spec.intensity() * a.env.value(partial);
	}

	private static final Identifier[][] GRAIN = new Identifier[3][8];
	private static final Identifier[][] HEAT = new Identifier[3][32];

	static {
		for (int l = 0; l < 3; l++) {
			for (int f = 0; f < 8; f++) {
				GRAIN[l][f] = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "grain_" + (l + 1) + "_" + f);
			}
			for (int f = 0; f < 32; f++) {
				HEAT[l][f] = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "heat_" + (l + 1) + "_" + f);
			}
		}
	}

	private static final float[] DOF_STEPS = {1.5f, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 26, 34, 45, 60};
	private static float dofFocus = 8;
	private static long dofLastMs;

	/** Where the lens focuses: a fixed distance, or (auto) the followed actor / whatever the camera looks at. */
	private static float focusDistance(String style) {
		if (!style.equals("auto")) {
			try {
				return Float.parseFloat(style);
			} catch (NumberFormatException e) {
				return 8;
			}
		}
		float planned = de.behindtheblocks.cinematic.client.camera.CinematicCameraController.pathFocus();
		if (planned > 0) {
			return planned; // focus set per point of the camera move
		}
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.Camera cam = mc.gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 cp = cam.position();
		net.minecraft.world.entity.Entity t = de.behindtheblocks.cinematic.client.camera.CinematicCameraController.focusEntity();
		if (t != null) {
			return (float) cp.distanceTo(t.getEyePosition());
		}
		if (mc.level == null) {
			return 8;
		}
		org.joml.Vector3fc fw = cam.forwardVector();
		net.minecraft.world.phys.Vec3 end = cp.add(fw.x() * 80, fw.y() * 80, fw.z() * 80);
		var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(cp, end, net.minecraft.world.level.ClipContext.Block.VISUAL,
				net.minecraft.world.level.ClipContext.Fluid.ANY, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		float d = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 60 : (float) hit.getLocation().distanceTo(cp);
		// an entity in the middle of the picture wins over the wall behind it
		var box = new net.minecraft.world.phys.AABB(cp, end).inflate(1);
		for (var e : mc.level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> e != mc.getCameraEntity() || mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON)) {
			var ehit = e.getBoundingBox().inflate(0.3).clip(cp, end);
			if (ehit.isPresent()) {
				d = Math.min(d, (float) ehit.get().distanceTo(cp));
			}
		}
		return d;
	}

	/** Where the focused subject is on screen (0..1, y up as in the shader), centre if there is none. */
	private static float[] focusScreenPoint() {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.world.entity.Entity t = de.behindtheblocks.cinematic.client.camera.CinematicCameraController.focusEntity();
		if (t == null) {
			return new float[] {0.5f, 0.5f};
		}
		net.minecraft.client.Camera cam = mc.gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 d = t.getEyePosition().add(0, -0.4, 0).subtract(cam.position());
		org.joml.Vector3fc fw = cam.forwardVector(), up = cam.upVector(), left = cam.leftVector();
		double z = d.x * fw.x() + d.y * fw.y() + d.z * fw.z();
		if (z < 0.3) {
			return new float[] {0.5f, 0.5f};
		}
		double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
		double tanHalf = Math.tan(Math.toRadians(cam.getFov()) / 2);
		double x = -(d.x * left.x() + d.y * left.y() + d.z * left.z()) / z / (tanHalf * aspect);
		double y = (d.x * up.x() + d.y * up.y() + d.z * up.z()) / z / tanHalf;
		return new float[] {(float) (0.5 + x / 2), (float) (0.5 + y / 2)};
	}

	/** Post shaders for this frame: depth of field, heat shimmer, streaks, flashback, grain on top. */
	public static void addPostEffects(List<Identifier> out) {
		if (Minecraft.getInstance().gui.screen() != null) {
			return;
		}
		Active dof = SLOTS.get(EffectSpec.Kind.DOF);
		float dofLevel = level(EffectSpec.Kind.DOF, partialNow());
		if (dof != null && dofLevel > 0.05f) {
			long now = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
			float dt = dofLastMs == 0 ? 1 : Math.min(1, (now - dofLastMs) / 1000f);
			dofLastMs = now;
			float target = Math.max(1.5f, Math.min(60, focusDistance(dof.spec.style())));
			dofFocus += (target - dofFocus) * Math.min(1, dt * 3.5f); // a focus puller, not an instant snap
			int best = 0;
			for (int i = 1; i < DOF_STEPS.length; i++) {
				if (Math.abs(Math.log(DOF_STEPS[i] / dofFocus)) < Math.abs(Math.log(DOF_STEPS[best] / dofFocus))) {
					best = i;
				}
			}
			int strength = dofLevel < 0.55f ? 1 : 2;
			boolean shaders = de.behindtheblocks.cinematic.client.model.ShaderCompat.shaderPackInUse();
			if (shaders && IrisDepth.refresh()) {
				// shader pack: real depth from Iris' own depth texture
				out.add(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "dofi_" + strength + "_" + best));
			} else if (shaders) {
				// no depth reachable: sharp area around the subject instead
				float[] c = focusScreenPoint();
				int xi = Math.round((Math.max(0.2f, Math.min(0.8f, c[0])) - 0.2f) / 0.15f);
				int yi = Math.round((Math.max(0.3f, Math.min(0.7f, c[1])) - 0.3f) / 0.2f);
				int ri = dofFocus < 4 ? 2 : dofFocus < 12 ? 1 : 0;
				out.add(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "dofs_" + strength + "_" + ri + "_" + xi + "_" + yi));
			} else {
				out.add(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "dof_" + strength + "_" + best));
			}
		}
		float heat = level(EffectSpec.Kind.HAZE, partialNow());
		if (heat > 0.05f) {
			// post shaders get no clock: the animation steps through precomputed phases (60 ms each)
			out.add(HEAT[heat < 0.4f ? 0 : heat < 0.75f ? 1 : 2][(int) (de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() / 60 % 32)]);
		}
		Active lut = SLOTS.get(EffectSpec.Kind.LUT);
		float lutLevel = level(EffectSpec.Kind.LUT, partialNow());
		if (lut != null && lutLevel > 0.05f && LutManager.load(lut.spec.style())) {
			out.add(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "lut_" + (lutLevel < 0.45f ? 1 : lutLevel < 0.8f ? 2 : 3)));
		}
		float ana = level(EffectSpec.Kind.ANAMORPH, partialNow());
		if (ana > 0.05f) {
			out.add(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "anamorph_" + (ana < 0.4f ? 1 : ana < 0.75f ? 2 : 3)));
		}
		float fb = level(EffectSpec.Kind.FLASHBACK, partialNow());
		if (fb > 0.05f) {
			out.add(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "flashback_" + (fb < 0.4f ? 1 : fb < 0.75f ? 2 : 3) + "_"
					+ (de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() / 120 % 16)));
		}
		float grain = level(EffectSpec.Kind.GRAIN, partialNow());
		if (grain > 0.05f) {
			out.add(GRAIN[grain < 0.4f ? 0 : grain < 0.75f ? 1 : 2][(int) (de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() / 42 % 8)]);
		}
	}

	/** Blur radius for the blur pass this frame (0 = no blur). */
	public static int blurRadius() {
		Active blur = SLOTS.get(EffectSpec.Kind.BLUR);
		if (blur == null || Minecraft.getInstance().gui.screen() != null) {
			return 0;
		}
		return Math.round(blur.spec.intensity() * blur.env.value(partialNow()));
	}

	private static float partialNow() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	// ------------------------------------------------------------------ drawing

	/** Effects drawn below HUD texts: blur, tint, vignette, letterbox. */
	public static void renderBase(GuiGraphicsExtractor g, float partial) {
		long now = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
		for (Active a : SLOTS.values()) {
			if (a.autoReleaseAt > 0 && now >= a.autoReleaseAt) {
				a.autoReleaseAt = 0;
				a.env.release(30, 0f);
			}
		}
		int w = g.guiWidth(), h = g.guiHeight();
		if (blurRadius() > 0 && de.behindtheblocks.cinematic.client.model.ShaderCompat.shaderPackInUse()) {
			// Shader packs replace Minecraft's blur pass: fall back to a soft haze so the moment still reads.
			float k = Math.min(1f, blurRadius() / 10f);
			fill(g, 0, 0, w, h, Colors.withAlpha(0xFFB8B0B0, 0.12f + 0.25f * k));
		} else if (blurRadius() > 0) {
			try {
				g.nextStratum();
				g.blurBeforeThisStratum();
			} catch (IllegalStateException alreadyBlurred) {
				// Something else blurred this frame; skip rather than crash.
			}
		}
		Active tint = SLOTS.get(EffectSpec.Kind.TINT);
		if (tint != null) {
			int color = currentColor(tint, partial);
			fill(g, 0, 0, w, h, Colors.withAlpha(color, tint.spec.intensity() * tint.env.value(partial)));
		}
		Active vignette = SLOTS.get(EffectSpec.Kind.VIGNETTE);
		if (vignette != null) {
			float a = vignette.spec.intensity() * vignette.env.value(partial);
			if (a > 0.004f) {
				g.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0, 0, w, h, 256, 256, 256, 256,
						Colors.withAlpha(currentColor(vignette, partial), a));
			}
		}
		heartPulse(g, w, h, partial);
		Active flare = SLOTS.get(EffectSpec.Kind.FLARE);
		if (flare != null) {
			drawFlare(g, flare, partial, w, h);
		}
		Active letterbox = SLOTS.get(EffectSpec.Kind.LETTERBOX);
		if (letterbox != null && letterbox.spec.style().startsWith("ratio:")) {
			// cinema format: bars top/bottom for wide formats, at the sides for narrow ones (4:3, 1:1)
			double ratio = Double.parseDouble(letterbox.spec.style().substring(6));
			float v = letterbox.env.value(partial);
			int color = 0xFF000000;
			if (ratio > w / (double) h) {
				int bar = (int) Math.round((h - w / ratio) / 2 * v);
				g.fill(0, 0, w, bar, color);
				g.fill(0, h - bar, w, h, color);
			} else {
				int bar = (int) Math.round((w - h * ratio) / 2 * v);
				g.fill(0, 0, bar, h, color);
				g.fill(w - bar, 0, w, h, color);
			}
		} else if (letterbox != null) {
			int bar = Math.round(h * letterbox.spec.intensity() * letterbox.env.value(partial));
			if (bar > 0) {
				int color = 0xFF000000 | (letterbox.spec.color() & 0xFFFFFF);
				g.fill(0, 0, w, bar, color);
				g.fill(0, h - bar, w, h, color);
			}
		}
	}

	/** Effects drawn over everything except "above effects" HUD texts: fade and transitions. */
	public static void renderCover(GuiGraphicsExtractor g, float partial) {
		int w = g.guiWidth(), h = g.guiHeight();
		Active fade = SLOTS.get(EffectSpec.Kind.FADE);
		if (fade != null) {
			fill(g, 0, 0, w, h, Colors.withAlpha(currentColor(fade, partial), fade.spec.intensity() * fade.env.value(partial)));
		}
		Active transition = SLOTS.get(EffectSpec.Kind.TRANSITION);
		if (transition != null) {
			drawTransition(g, transition, partial, w, h);
		}
	}

	/** Flashes: top-most so they also flare over titles. */
	public static void renderFlashes(GuiGraphicsExtractor g, float partial) {
		int w = g.guiWidth(), h = g.guiHeight();
		for (Active f : FLASHES) {
			fill(g, 0, 0, w, h, Colors.withAlpha(f.spec.color(), f.spec.intensity() * f.env.value(partial)));
		}
	}

	/**
	 * Whoever carries the Heart of the Nether sees its beat: a dark red edge that swells with every
	 * heartbeat (2 s, the same rhythm as the heart model and the tower), double-pulse like a real heart.
	 */
	private static void heartPulse(GuiGraphicsExtractor g, int w, int h, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.screen() != null) {
			return;
		}
		boolean holds = mc.player.getMainHandItem().is(de.behindtheblocks.cinematic.content.BtbItems.HEART_OF_THE_NETHER)
				|| mc.player.getOffhandItem().is(de.behindtheblocks.cinematic.content.BtbItems.HEART_OF_THE_NETHER);
		if (!holds || mc.level == null) {
			return;
		}
		double t = ((mc.level.getGameTime() % 40) + partial) / 40.0;
		double beat = Math.exp(-Math.pow((t - 0.06) / 0.045, 2)) + 0.6 * Math.exp(-Math.pow((t - 0.24) / 0.05, 2));
		float a = (float) Math.min(1, 0.35 + 0.65 * beat);
		g.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0, 0, w, h, 256, 256, 256, 256, Colors.withAlpha(0xFF6E0606, a));
		if (beat > 0.3) { // the strong beat reaches further into the picture
			g.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0, 0, w, h, 256, 256, 256, 256, Colors.withAlpha(0xFF8A0A0A, (float) (beat - 0.3) * 0.6f));
		}
	}

	private static void drawTransition(GuiGraphicsExtractor g, Active t, float partial, int w, int h) {
		String style = t.spec.style();
		float p = t.env.value(partial);
		float cover = style.endsWith("_open") ? 1 - p : p;
		if (cover <= 0.001f) {
			return;
		}
		int color = 0xFF000000 | (t.spec.color() & 0xFFFFFF);
		switch (style) {
			case "wipe_left" -> g.fill(0, 0, Math.round(w * cover), h, color);
			case "wipe_right" -> g.fill(w - Math.round(w * cover), 0, w, h, color);
			case "wipe_down" -> g.fill(0, 0, w, Math.round(h * cover), color);
			case "wipe_up" -> g.fill(0, h - Math.round(h * cover), w, h, color);
			case "bars_close", "bars_open" -> {
				int bar = Math.round(h / 2f * cover);
				g.fill(0, 0, w, bar, color);
				g.fill(0, h - bar, w, h, color);
			}
			case "iris_close", "iris_open" -> {
				float cx = w / 2f, cy = h / 2f;
				float maxR = (float) Math.sqrt(cx * cx + cy * cy) + 1;
				float r = maxR * (1 - cover);
				for (int y = 0; y < h; y++) {
					float dy = y + 0.5f - cy;
					if (Math.abs(dy) >= r) {
						g.fill(0, y, w, y + 1, color);
					} else {
						int dx = Math.round((float) Math.sqrt(r * r - dy * dy));
						g.fill(0, y, Math.max(0, Math.round(cx) - dx), y + 1, color);
						g.fill(Math.min(w, Math.round(cx) + dx), y, w, y + 1, color);
					}
				}
			}
			default -> { }
		}
	}

	private static final Identifier FLARE_GLOW = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "textures/gui/flare_glow.png");
	private static final Identifier FLARE_RING = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "textures/gui/flare_ring.png");
	/** Smoothed visibility of the flare source (occlusion fades instead of popping). */
	private static float flareVisibility;
	private static long flareLastMs;
	private static float flareSx, flareSy;

	/**
	 * Projects the world point onto the screen and draws a glow, an anamorphic streak and a chain of
	 * ghosts through the screen centre. One raycast per frame decides whether the source is hidden.
	 */
	private static void drawFlare(GuiGraphicsExtractor g, Active a, float partial, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		String[] p = a.spec.style().split(",");
		net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
		net.minecraft.client.Camera cam = mc.gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 cp = cam.position();
		net.minecraft.world.phys.Vec3 d = pos.subtract(cp);
		org.joml.Vector3fc fw = cam.forwardVector(), up = cam.upVector(), left = cam.leftVector();
		double z = d.x * fw.x() + d.y * fw.y() + d.z * fw.z();
		float target = 0;
		float sx = flareSx, sy = flareSy; // behind the camera: fade out where it was last seen, not in a corner
		if (z > 0.5) {
			double x = -(d.x * left.x() + d.y * left.y() + d.z * left.z()) / z;
			double y = (d.x * up.x() + d.y * up.y() + d.z * up.z()) / z;
			double focal = (h / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2);
			sx = (float) (w / 2.0 + x * focal);
			sy = (float) (h / 2.0 - y * focal);
			// soft edge: fades out over the last fifth outside the picture instead of popping
			float ex = (float) Math.max(0, Math.max(-sx, sx - w) / (w * 0.25));
			float ey = (float) Math.max(0, Math.max(-sy, sy - h) / (h * 0.25));
			float edge = Math.max(0, 1 - Math.max(ex, ey));
			// occlusion: nine rays around the light – thin bars, chains or spikes only dim it a little,
			// a wall hides it; blocks right at the light (lamp, tower tip) never count
			int open = 0;
			// far away the rays spread wider (a few blocks at 200 m), so single spikes or bars never switch it off
			double rr = Math.min(4.0, 0.02 * d.length() + 0.4);
			for (int i = 0; i < 9; i++) {
				net.minecraft.world.phys.Vec3 t = i == 0 ? pos : pos.add(left.x() * rr * Math.cos(i * 0.785) + up.x() * rr * Math.sin(i * 0.785),
						left.y() * rr * Math.cos(i * 0.785) + up.y() * rr * Math.sin(i * 0.785),
						left.z() * rr * Math.cos(i * 0.785) + up.z() * rr * Math.sin(i * 0.785));
				// only solid blocks hide the light (glass, bars, chains, leaves and plants do not)
				var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(cp, t, net.minecraft.world.level.ClipContext.Block.COLLIDER,
						net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
				if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceTo(cp) >= t.distanceTo(cp) - 3.0) {
					open++;
				}
			}
			target = edge * open / 9f;
			flareSx = sx;
			flareSy = sy;
		}
		// frame-rate independent smoothing (≈ 0.12 s)
		float dt = Math.min(0.1f, (de.behindtheblocks.cinematic.client.export.ExportClock.nowMs() - flareLastMs) / 1000f);
		flareLastMs = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
		flareVisibility += (target - flareVisibility) * Math.min(1f, dt * 8f);
		float alpha = flareVisibility * a.spec.intensity() * a.env.value(partial);
		if (alpha < 0.01f) {
			return;
		}
		int color = a.spec.color();
		int size = (int) (Math.min(w, h) * 0.55f);
		blitCentered(g, FLARE_GLOW, sx, sy, size, Colors.withAlpha(color, alpha));
		blitCentered(g, FLARE_GLOW, sx, sy, size / 4, Colors.withAlpha(0xFFFFFFFF, alpha));
		// anamorphic streak
		int streakW = (int) (w * 0.9f), streakH = Math.max(2, size / 40);
		g.blit(RenderPipelines.GUI_TEXTURED, FLARE_GLOW, (int) sx - streakW / 2, (int) sy - streakH / 2, 0, 0, streakW, streakH, 64, 64, 64, 64,
				Colors.withAlpha(color, alpha * 0.6f));
		// ghosts along the line through the centre
		float cx = w / 2f, cy = h / 2f;
		float[][] ghosts = {{-0.35f, 0.12f}, {0.25f, 0.06f}, {0.6f, 0.18f}, {1.1f, 0.09f}, {1.45f, 0.3f}};
		int[] tints = {0xFF80C0FF, 0xFFFFC080, 0xFFC080FF, 0xFF80FFC0, 0xFFFFE0A0};
		for (int i = 0; i < ghosts.length; i++) {
			float gx = sx + (cx - sx) * (1 + ghosts[i][0]) , gy = sy + (cy - sy) * (1 + ghosts[i][0]);
			int gs = (int) (size * ghosts[i][1]);
			blitCentered(g, i % 2 == 0 ? FLARE_RING : FLARE_GLOW, gx, gy, gs, Colors.withAlpha(tints[i], alpha * 0.35f));
		}
	}

	private static void blitCentered(GuiGraphicsExtractor g, Identifier tex, float x, float y, int size, int argb) {
		if ((argb >>> 24) < 3 || size < 1) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, tex, (int) x - size / 2, (int) y - size / 2, 0, 0, size, size, 64, 64, 64, 64, argb);
	}

	private static void fill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
		if ((argb >>> 24) > 0) {
			g.fill(x0, y0, x1, y1, argb);
		}
	}

	public static void clearAll() {
		SLOTS.clear();
		FLASHES.clear();
	}
}
