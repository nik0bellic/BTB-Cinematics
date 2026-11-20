package de.behindtheblocks.cinematic.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BTB Skin Studio (optional): remembers its server sync object (so replays can store the actors'
 * looks) and puts actors' emotes into the replay that is being recorded. Without Skin Studio this
 * mixin simply does nothing.
 */
@Pseudo
@Mixin(targets = "de.behindtheblocks.skinstudio.server.ServerCosmeticSync", remap = false)
public abstract class SkinStudioEmoteMixin {
	@Inject(method = "register", at = @At("HEAD"), require = 0)
	private void btb$remember(CallbackInfo ci) {
		de.behindtheblocks.cinematic.replay.SkinStudioBridge.attach(this);
	}

	@Inject(method = "onEmote", at = @At("HEAD"), require = 0)
	private void btb$recordEmote(ServerPlayer player, String id, MinecraftServer server, CallbackInfo ci) {
		de.behindtheblocks.cinematic.replay.ReplayManager.log("emote", player.getUUID() + ":" + id);
	}
}
