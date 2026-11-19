package de.behindtheblocks.cinematic.showcase;

import com.mojang.math.Transformation;
import de.behindtheblocks.cinematic.camera.Easing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One running showcase in the world.
 *
 * <p>Built from vanilla item display entities: the server computes each object's transform per
 * preset and sends it every {@link #UPDATE_INTERVAL} ticks with matching interpolation, so clients
 * render perfectly smooth motion (also in recordings) at minimal network cost. The displayed item
 * can be any item, including GeckoLib-animated ones like the Heart of the Nether, whose own bone
 * animation runs on top of the showcase motion.
 */
final class ShowcaseInstance {
	static final String ENTITY_TAG = "btb_showcase";
	static final int UPDATE_INTERVAL = 2;
	private static final int REVEAL_TICKS = 50;
	private static final int ASCEND_TICKS = 80;
	private static final int EXPLODE_CHARGE = 20;
	private static final int EXPLODE_FLY = 40;
	private static final int BEAT = 40; // = the 2 s idle loop of the GeckoLib heart (lub at 0.12 s, dub at 0.38 s)

	final ShowcaseDefinition def;
	final String sceneId;
	final ServerLevel level;
	ShowcasePreset preset;
	int t;
	private int presetStart;
	private Display.ItemDisplay main;
	private final List<Display.ItemDisplay> satellites = new ArrayList<>();
	private final List<Fragment> fragments = new ArrayList<>();
	private BlockPos lightPos;
	private BlockState lightPrevious;
	/** Hooks to trigger sounds/effects from the manager (keeps this class free of module refs). */
	private final Effects effects;

	interface Effects {
		/** Called right before an entity is added to the world, so cleanup logic knows it is ours. */
		void own(UUID entity);

		void beat(ShowcaseInstance s);

		void burst(ShowcaseInstance s, boolean big);
	}

	private record Fragment(Display.ItemDisplay entity, Vector3f velocity, Vector3f spin) {
	}

	ShowcaseInstance(ShowcaseDefinition def, ServerLevel level, String sceneId, ShowcasePreset preset, Effects effects) {
		this.def = def;
		this.level = level;
		this.sceneId = sceneId;
		this.preset = preset;
		this.effects = effects;
	}

	void start() {
		main = spawn(new ItemStack(def.item()));
		for (ShowcaseDefinition.Satellite s : def.satellites()) {
			for (int i = 0; i < s.count(); i++) {
				satellites.add(spawn(new ItemStack(s.item())));
			}
		}
		if (def.light()) {
			BlockPos pos = BlockPos.containing(def.x(), def.y(), def.z());
			BlockState prev = level.getBlockState(pos);
			if (prev.isAir()) {
				lightPrevious = prev;
				lightPos = pos;
				level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
			}
		}
		apply(true);
	}

	private Display.ItemDisplay spawn(ItemStack stack) {
		Display.ItemDisplay d = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		d.setPos(def.x(), def.y(), def.z());
		d.setItemStack(stack);
		d.setItemTransform(ItemDisplayContext.FIXED);
		d.setBrightnessOverride(Brightness.FULL_BRIGHT);
		d.setViewRange(2.0f);
		d.setTransformationInterpolationDuration(UPDATE_INTERVAL);
		d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.001f), null));
		// No vanilla "glowing" outline: under shader packs it draws black outlines/streaks.
		// The glow colour is shown by the light and particles instead.
		d.addTag(ENTITY_TAG);
		effects.own(d.getUUID());
		level.addFreshEntity(d);
		return d;
	}

	void switchPreset(ShowcasePreset next) {
		preset = next;
		presetStart = t;
	}

	/** @return false when finished */
	boolean tick() {
		t++;
		// Discrete moments are checked every tick (transform updates only run every UPDATE_INTERVAL).
		int lt = t - presetStart;
		switch (preset) {
			case REVEAL, SHOWCASE -> {
				if (lt == REVEAL_TICKS - 6) {
					effects.burst(this, true);
				}
			}
			case PULSE -> {
				if (lt % BEAT == 0) {
					effects.beat(this);
				}
			}
			case EXPLODE -> {
				if (lt == EXPLODE_CHARGE) {
					shatter();
				}
			}
			default -> { }
		}
		if (t % UPDATE_INTERVAL == 0) {
			apply(false);
		}
		spawnParticles();
		tickFragments();
		return def.duration() < 0 || t < def.duration();
	}

	// ------------------------------------------------------------------ motion

	private void apply(boolean instant) {
		int lt = t - presetStart;
		float s = def.scale();
		float dy = 0, yaw = 0, pitch = 0, roll = 0, scale = s;
		switch (preset) {
			case FLOAT -> {
				dy = (float) Math.sin(t * 0.1) * 0.12f;
				yaw = t * 1.2f;
			}
			case ROTATE -> {
				yaw = t * 4f;
				pitch = (float) Math.sin(t * 0.05) * 18;
				roll = (float) Math.sin(t * 0.07) * 12;
				dy = (float) Math.sin(t * 0.08) * 0.05f;
			}
			case REVEAL, SHOWCASE -> {
				if (lt < REVEAL_TICKS) {
					float p = lt / (float) REVEAL_TICKS;
					scale = s * (float) Easing.EASE_OUT_BACK.apply(p);
					dy = -0.6f * (1 - (float) Easing.EASE_OUT.apply(p));
					yaw = 540f * (float) Easing.EASE_OUT.apply(p);
				} else {
					int ft = lt - REVEAL_TICKS;
					yaw = 540f + ft * (preset == ShowcasePreset.SHOWCASE ? 1.6f : 1.2f);
					dy = (float) Math.sin(ft * 0.1) * 0.12f;
					if (preset == ShowcasePreset.SHOWCASE) {
						pitch = (float) Math.sin(ft * 0.04) * 8;
					}
				}
			}
			case PULSE -> {
				int phase = lt % BEAT;
				float beat = bump(phase, 2, 3) + 0.6f * bump(phase, 7, 3); // same lub-dub as the model animation
				scale = s * (1 + 0.13f * beat);
				yaw = t * 0.8f;
			}
			case ASCEND -> {
				float p = Math.min(1, lt / (float) ASCEND_TICKS);
				dy = 2.5f * (float) Easing.EASE_IN_OUT.apply(p) + (p >= 1 ? (float) Math.sin(t * 0.1) * 0.1f : 0);
				yaw = (float) (Math.pow(lt, 1.6) * 0.35);
			}
			case EXPLODE -> {
				if (lt < EXPLODE_CHARGE) {
					float p = lt / (float) EXPLODE_CHARGE;
					scale = s * (1 + 0.4f * p);
					yaw = t * (4 + 20 * p);
					dy = (float) Math.sin(t * 7.3) * 0.03f * p; // deterministic tremble
				} else {
					scale = 0.001f;
				}
			}
		}
		setTransform(main, 0, dy, 0, yaw, pitch, roll, scale, instant);
		applySatellites(instant);
	}

	private void applySatellites(boolean instant) {
		if (satellites.isEmpty()) {
			return;
		}
		boolean visible = preset == ShowcasePreset.SHOWCASE ? t - presetStart >= REVEAL_TICKS - 10 : preset != ShowcasePreset.EXPLODE;
		int idx = 0;
		for (ShowcaseDefinition.Satellite s : def.satellites()) {
			for (int i = 0; i < s.count(); i++, idx++) {
				double angle = Math.toRadians(t * s.speed() * 3) + i * Math.PI * 2 / s.count();
				float grow = preset == ShowcasePreset.SHOWCASE
						? (float) Easing.EASE_OUT.apply(Math.min(1, Math.max(0, (t - presetStart - (REVEAL_TICKS - 10)) / 30.0))) : 1;
				float sc = visible ? s.scale() * grow : 0.001f;
				float r = s.radius() * grow;
				setTransform(satellites.get(idx), (float) Math.cos(angle) * r,
						s.height() + (float) Math.sin(t * 0.12 + i) * 0.1f, (float) Math.sin(angle) * r,
						(float) -Math.toDegrees(angle) + t * 2, 0, 15, sc, instant);
			}
		}
	}

	private static float bump(int phase, int start, int len) {
		int d = phase - start;
		if (d < 0 || d > 2 * len) {
			return 0;
		}
		return (float) Math.sin(Math.PI * d / (2.0 * len));
	}

	private static void setTransform(Display.ItemDisplay d, float x, float y, float z, float yaw, float pitch, float roll,
			float scale, boolean instant) {
		if (d == null || d.isRemoved()) {
			return;
		}
		Quaternionf q = new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch),
				(float) Math.toRadians(roll));
		d.setTransformationInterpolationDuration(instant ? 0 : UPDATE_INTERVAL);
		d.setTransformation(new Transformation(new Vector3f(x, y, z), q, new Vector3f(scale), null));
		d.setTransformationInterpolationDelay(0);
	}

	// ------------------------------------------------------------------ explode

	private void shatter() {
		effects.burst(this, true);
		java.util.Random rnd = new java.util.Random(def.id().hashCode()); // reproducible fragments
		for (int i = 0; i < 10; i++) {
			Display.ItemDisplay f = spawn(new ItemStack(def.item()));
			double theta = rnd.nextDouble() * Math.PI * 2, phi = Math.acos(2 * rnd.nextDouble() - 1);
			float speed = 0.12f + rnd.nextFloat() * 0.1f;
			Vector3f v = new Vector3f((float) (Math.sin(phi) * Math.cos(theta)), (float) Math.abs(Math.cos(phi)) * 0.8f + 0.2f,
					(float) (Math.sin(phi) * Math.sin(theta))).mul(speed);
			fragments.add(new Fragment(f, v, new Vector3f(rnd.nextFloat() * 20 - 10, rnd.nextFloat() * 20 - 10, rnd.nextFloat() * 20 - 10)));
		}
	}

	private void tickFragments() {
		if (fragments.isEmpty()) {
			return;
		}
		int ft = t - presetStart - EXPLODE_CHARGE;
		if (ft > EXPLODE_FLY) {
			fragments.forEach(f -> f.entity.discard());
			fragments.clear();
			return;
		}
		if (t % UPDATE_INTERVAL != 0) {
			return;
		}
		float life = 1 - ft / (float) EXPLODE_FLY;
		for (Fragment f : fragments) {
			float x = f.velocity.x * ft, z = f.velocity.z * ft;
			float y = f.velocity.y * ft - 0.004f * ft * ft;
			setTransform(f.entity, x, y, z, f.spin.x * ft, f.spin.y * ft, f.spin.z * ft, def.scale() * 0.35f * life, false);
			if (ft % 4 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, def.x() + x, def.y() + y, def.z() + z, 1, 0, 0, 0, 0.01);
			}
		}
	}

	// ------------------------------------------------------------------ particles

	private void spawnParticles() {
		ParticleOptions p = def.particle();
		if (p == null) {
			return;
		}
		double cx = def.x(), cy = def.y(), cz = def.z();
		switch (def.background()) {
			case "ring" -> {
				if (t % 2 == 0) {
					for (int i = 0; i < 3; i++) {
						double a = t * 0.08 + i * Math.PI * 2 / 3;
						double r = 1.2 + def.scale() * 0.3;
						level.sendParticles(p, cx + Math.cos(a) * r, cy - 0.2, cz + Math.sin(a) * r, 1, 0, 0.02, 0, 0.005);
					}
				}
			}
			case "embers" -> {
				if (t % 3 == 0) {
					level.sendParticles(p, cx, cy - 0.5, cz, 2, 0.8, 0.1, 0.8, 0.02);
					level.sendParticles(ParticleTypes.LAVA, cx, cy - 0.5, cz, 1, 0.6, 0, 0.6, 0);
				}
			}
			case "pillar" -> {
				if (t % 2 == 0) {
					level.sendParticles(p, cx, cy - 1.0 + (t % 40) * 0.08, cz, 2, 0.15, 0.05, 0.15, 0.003);
				}
			}
			case "aura" -> {
				if (t % 2 == 0) {
					level.sendParticles(p, cx, cy, cz, 2, 0.5 * def.scale(), 0.5 * def.scale(), 0.5 * def.scale(), 0.01);
				}
			}
			default -> { }
		}
		if (preset == ShowcasePreset.ASCEND && t - presetStart < ASCEND_TICKS && t % 2 == 0) {
			float p2 = (t - presetStart) / (float) ASCEND_TICKS;
			level.sendParticles(p, cx, cy + 2.5 * Easing.EASE_IN_OUT.apply(p2) - 0.3, cz, 2, 0.1, 0.05, 0.1, 0.01);
		}
	}

	// ------------------------------------------------------------------ cleanup

	void remove() {
		if (main != null) {
			main.discard();
		}
		satellites.forEach(Display.ItemDisplay::discard);
		satellites.clear();
		fragments.forEach(f -> f.entity.discard());
		fragments.clear();
		if (lightPos != null && level.getBlockState(lightPos).is(Blocks.LIGHT)) {
			level.setBlock(lightPos, lightPrevious, 3);
		}
		lightPos = null;
	}

	List<UUID> entityIds() {
		List<UUID> ids = new ArrayList<>();
		if (main != null) {
			ids.add(main.getUUID());
		}
		satellites.forEach(s -> ids.add(s.getUUID()));
		fragments.forEach(f -> ids.add(f.entity.getUUID()));
		return ids;
	}
}
