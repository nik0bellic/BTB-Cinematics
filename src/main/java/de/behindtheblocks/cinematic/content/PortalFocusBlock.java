package de.behindtheblocks.cinematic.content;

import com.geckolib.animatable.GeoBlockEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Portalfokus: a pedestal with a floating crystal and two golden rings spinning around it (animated
 * GeckoLib model). Place them in pairs beside the portal.
 */
public class PortalFocusBlock extends Block implements EntityBlock {
	private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 8, 14);

	public PortalFocusBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new Entity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
		double x = pos.getX() + 0.5, y = pos.getY() + 1.0, z = pos.getZ() + 0.5;
		if (r.nextInt(2) == 0) {
			level.addParticle(ParticleTypes.END_ROD, x + (r.nextDouble() - 0.5) * 0.8, y + r.nextDouble() * 0.6, z + (r.nextDouble() - 0.5) * 0.8,
					0, 0.01, 0);
		}
		if (r.nextInt(6) == 0) {
			level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y + 0.3, z, (r.nextDouble() - 0.5) * 0.2, 0.05, (r.nextDouble() - 0.5) * 0.2);
		}
	}

	public static class Entity extends BlockEntity implements GeoBlockEntity {
		private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
		private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

		public Entity(BlockPos pos, BlockState state) {
			super(BtbBlocks.PORTAL_FOCUS_ENTITY, pos, state);
		}

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
			controllers.add(new AnimationController<Entity>("main", 0, test -> test.setAndContinue(IDLE)));
		}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return cache;
		}
	}
}
