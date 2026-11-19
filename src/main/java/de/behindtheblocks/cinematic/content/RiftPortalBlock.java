package de.behindtheblocks.cinematic.content;

import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The animated portal surface. Placed and removed only by the {@link PortalManager}; walking into
 * it starts the seamless transition when the portal is ENTERABLE.
 */
public final class RiftPortalBlock extends Block {
	/** Plane normal: X/Z = upright portals, Y = portal lying on the floor (or ceiling). */
	public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
	public static final EnumProperty<PortalStyle> STYLE = EnumProperty.create("style", PortalStyle.class);
	private static final VoxelShape X_SHAPE = Block.box(0, 0, 6, 16, 16, 10);
	private static final VoxelShape Z_SHAPE = Block.box(6, 0, 0, 10, 16, 16);
	private static final VoxelShape Y_SHAPE = Block.box(0, 6, 0, 16, 10, 16);

	public RiftPortalBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(STYLE, PortalStyle.NETHER));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AXIS, STYLE);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(AXIS)) {
			case X -> X_SHAPE;
			case Y -> Y_SHAPE;
			case Z -> Z_SHAPE;
		};
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects,
			boolean precise) {
		if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player) {
			BtbCinematic.engine().module(PortalManager.class).ifPresent(pm -> pm.onPlayerInside(serverLevel, pos, player));
		}
	}
}
