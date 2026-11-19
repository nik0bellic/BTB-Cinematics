package de.behindtheblocks.cinematic.content;

import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The keyhole of a portal. Using the Nether Key on it starts the activation sequence of the portal
 * that owns this keystone (see {@link PortalManager}).
 */
public final class PortalKeystoneBlock extends Block {
	public static final EnumProperty<KeystoneStage> STAGE = EnumProperty.create("stage", KeystoneStage.class);

	public PortalKeystoneBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(STAGE, KeystoneStage.IDLE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(STAGE);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		if (!stack.is(BtbItems.NETHER_KEY)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (level instanceof ServerLevel serverLevel) {
			BtbCinematic.engine().module(PortalManager.class).ifPresent(pm -> pm.onKeyUsed(serverLevel, pos, player));
		}
		return InteractionResult.SUCCESS;
	}
}
