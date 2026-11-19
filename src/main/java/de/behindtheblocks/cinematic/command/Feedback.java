package de.behindtheblocks.cinematic.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Consistent chat formatting for command feedback. */
public final class Feedback {
	private Feedback() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[BTB] ").withStyle(ChatFormatting.GOLD);
	}

	public static int ok(CommandSourceStack source, String message) {
		// broadcastToAdmins = false: other operators (actors who record) don't get chat spam.
		source.sendSuccess(() -> prefix().append(Component.literal(message).withStyle(ChatFormatting.WHITE)), false);
		return 1;
	}

	public static int info(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	public static int fail(CommandSourceStack source, String message) {
		source.sendFailure(prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
		return 0;
	}
}
