package de.behindtheblocks.cinematic.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

/** Implemented by modules that add a sub-command below {@code /btb}. */
public interface CommandContributor {
	void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext);
}
