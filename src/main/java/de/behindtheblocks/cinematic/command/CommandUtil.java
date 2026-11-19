package de.behindtheblocks.cinematic.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/** Helpers shared by module command trees. */
public final class CommandUtil {
	private CommandUtil() {
	}

	/**
	 * Resolves an optional {@code targets} player-selector argument. Without it: the executing
	 * player, or everyone if run from the console / a command block.
	 */
	public static Collection<ServerPlayer> targets(CommandContext<CommandSourceStack> ctx, boolean hasArgument)
			throws CommandSyntaxException {
		if (hasArgument) {
			try {
				return EntityArgument.getPlayers(ctx, "targets");
			} catch (CommandSyntaxException none) {
				// Film scenes aim camera moves at @a[tag=kamera] (the recording client). Nobody tagged yet
				// (e.g. testing alone) → everyone sees the move instead of nothing happening.
				if (ctx.getInput().contains("tag=kamera")) {
					return List.copyOf(ctx.getSource().getServer().getPlayerList().getPlayers());
				}
				throw none;
			}
		}
		ServerPlayer self = ctx.getSource().getPlayer();
		return self != null ? List.of(self) : List.copyOf(ctx.getSource().getServer().getPlayerList().getPlayers());
	}

	/** Seconds → ticks, at least 1. */
	public static int ticks(double seconds) {
		return Math.max(1, (int) Math.round(seconds * 20.0));
	}
}
