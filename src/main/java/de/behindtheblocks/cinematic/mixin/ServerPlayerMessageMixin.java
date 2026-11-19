package de.behindtheblocks.cinematic.mixin;

import de.behindtheblocks.cinematic.util.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every system message to a player (command feedback, notices, clickable scene steps) in that player's language. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMessageMixin {
	@ModifyVariable(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), argsOnly = true)
	private Component btb$translate(Component message) {
		return I18n.translate(message, I18n.german((ServerPlayer) (Object) this));
	}
}
