package de.behindtheblocks.cinematic.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BTB Skin Studio (optional): an actor's emote goes into the replay that is being recorded, so the
 * puppet plays it again. Without Skin Studio this mixin simply does nothing.
 */
@Pseudo
@Mixin(targets = "de.behindtheblocks.skinstudio.server.ServerCosmeticSync", remap = false)
public abstract class SkinStudioEmoteMixin {
	@Inject(method = "onEmote", at = @At("HEAD"), require = 0)
	private void btb$recordEmote(ServerPlayer player, String id, MinecraftServer server, CallbackInfo ci) {
		de.behindtheblocks.cinematic.replay.ReplayManager.log("emote", player.getUUID() + ":" + id);
	}
}
