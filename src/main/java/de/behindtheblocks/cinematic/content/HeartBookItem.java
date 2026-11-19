package de.behindtheblocks.cinematic.content;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Das alte Buch: right-click opens the page with the message ("If you want to leave this place,
 * find the Heart of the Nether") with the ink effect – only for the one reading it.
 */
public class HeartBookItem extends Item {
	public HeartBookItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
			sl.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 0.8f);
			sl.getServer().getCommands().performPrefixedCommand(sl.getServer().createCommandSourceStack().withSuppressedOutput(),
					"btb hud show book_heart " + sp.getGameProfile().name());
			player.getCooldowns().addCooldown(player.getItemInHand(hand), 60);
		}
		return InteractionResult.SUCCESS;
	}
}
