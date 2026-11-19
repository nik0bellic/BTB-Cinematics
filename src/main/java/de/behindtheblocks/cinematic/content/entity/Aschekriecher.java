package de.behindtheblocks.cinematic.content.entity;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;

/** Aschekriecher: a low six-legged crawler of ash and blackstone with glowing eyes; climbs walls, comes in packs. */
public class Aschekriecher extends Spider implements GeoEntity {
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public Aschekriecher(EntityType<? extends Spider> type, Level level) {
		super(type, level);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level() instanceof ServerLevel sl && tickCount % 8 == 0 && getDeltaMovement().horizontalDistanceSqr() > 0.002) {
			sl.sendParticles(ParticleTypes.ASH, getX(), getY() + 0.2, getZ(), 3, 0.3, 0.05, 0.3, 0.01);
		}
	}

	@Override
	public boolean fireImmune() {
		return true;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<Aschekriecher>("main", 3, test -> test.setAndContinue(test.isMoving() ? WALK : IDLE)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
