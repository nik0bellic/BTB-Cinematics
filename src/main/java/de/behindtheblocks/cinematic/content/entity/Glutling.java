package de.behindtheblocks.cinematic.content.entity;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.Level;

/** Glutling: a small ember spirit that swarms through walls – a glowing core in a cage of shards, flame wings. */
public class Glutling extends Vex implements GeoEntity {
	private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public Glutling(EntityType<? extends Vex> type, Level level) {
		super(type, level);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level() instanceof ServerLevel sl && tickCount % 3 == 0) {
			sl.sendParticles(ParticleTypes.SMALL_FLAME, getX(), getY() + 0.35, getZ(), 1, 0.1, 0.1, 0.1, 0.005);
		}
	}

	@Override
	public boolean doHurtTarget(ServerLevel level, Entity target) {
		target.igniteForSeconds(2);
		return super.doHurtTarget(level, target);
	}

	@Override
	public boolean fireImmune() {
		return true;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<Glutling>("main", 2, test -> test.setAndContinue(FLY)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
