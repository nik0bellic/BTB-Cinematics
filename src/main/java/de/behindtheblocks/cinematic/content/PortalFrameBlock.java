package de.behindtheblocks.cinematic.content;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Blackstone-and-gold portal frame; its runes light up while the portal charges ({@code lit=true}). */
public final class PortalFrameBlock extends Block {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public PortalFrameBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}
}
