package de.behindtheblocks.cinematic.mixin;

import com.mojang.brigadier.ParseResults;
import de.behindtheblocks.cinematic.scene.SceneEditor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Live scene recording: every command a player runs passes {@code Commands.performCommand}
 * (typed, Director buttons and cue keys alike). Target: {@code Commands.performCommand(ParseResults, String)} (26.3).
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Inject(method = "performCommand", at = @At("HEAD"))
	private void btb$recordForScene(ParseResults<CommandSourceStack> command, String commandString, CallbackInfo ci) {
		SceneEditor editor = SceneEditor.instance();
		if (editor != null) {
			editor.onPlayerCommand(command, commandString);
		}
	}
}
