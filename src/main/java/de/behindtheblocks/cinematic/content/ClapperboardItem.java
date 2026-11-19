package de.behindtheblocks.cinematic.content;

import de.behindtheblocks.cinematic.sound.BtbSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Regieklappe (clapperboard). Right-click: a loud, sharp clap at the start of a take – in the editor
 * the spike in the audio track lines up picture and sound – plus "Take N" for the one holding it.
 * Sneak + right-click (operators): new take – the whole set is reset ({@code /btb resetall}).
 */
public class ClapperboardItem extends Item {
	private static int take;

	public ClapperboardItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
			if (player.isShiftKeyDown()) {
				if (!sl.getServer().getPlayerList().isOp(sp.nameAndId())) {
					sp.sendSystemMessage(Component.literal("Nur Operatoren können das Set zurücksetzen"), true);
					return InteractionResult.FAIL;
				}
				sl.getServer().getCommands().performPrefixedCommand(sl.getServer().createCommandSourceStack().withSuppressedOutput(), "btb resetall");
				sp.sendSystemMessage(Component.literal("Set zurückgesetzt – bereit für Take " + (take + 1)), true);
			} else {
				take++;
				sl.playSound(null, player.blockPosition(), BtbSounds.CLAP, SoundSource.MASTER, 2.5f, 1f);
				sp.sendSystemMessage(Component.literal("Take " + take), true);
			}
			player.getCooldowns().addCooldown(player.getItemInHand(hand), 10);
		}
		return InteractionResult.SUCCESS;
	}
}
