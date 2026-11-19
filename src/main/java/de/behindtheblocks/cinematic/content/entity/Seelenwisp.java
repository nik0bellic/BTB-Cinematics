package de.behindtheblocks.cinematic.content.entity;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.level.Level;

/** Seelenwisp: a harmless drifting soul light – atmosphere for the portal hall and dark corners. Silent. */
public class Seelenwisp extends Bat implements GeoEntity {
	private static final RawAnimation FLOAT = RawAnimation.begin().thenLoop("float");
	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public Seelenwisp(EntityType<? extends Bat> type, Level level) {
		super(type, level);
	}

	@Override
	public boolean isResting() {
		return false; // never hangs upside down like a bat
	}

	@Override
	public void aiStep() {
		super.aiStep();
		setDeltaMovement(getDeltaMovement().scale(0.85)); // drifts slowly instead of fluttering
		if (level() instanceof ServerLevel sl && tickCount % 6 == 0) {
			sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 0.25, getZ(), 1, 0.05, 0.05, 0.05, 0.002);
		}
	}

	@Override
	public SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return null;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return null;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<Seelenwisp>("main", 2, test -> test.setAndContinue(FLOAT)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
