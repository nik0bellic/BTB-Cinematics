package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.export.ExportManager;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Film export: the client runs exactly the ticks of the current frame with an exact partial tick. Target: DeltaTracker.Timer (26.3). */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTimerMixin {
	@Shadow
	private float deltaTickResidual;
	@Shadow
	private float deltaTicks;

	@Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
	private void btb$exportTicks(long currentMs, CallbackInfoReturnable<Integer> cir) {
		if (ExportManager.active()) {
			int ticks = ExportManager.ticksForFrame();
			deltaTicks = ticks;
			deltaTickResidual = ExportManager.partialForFrame();
			cir.setReturnValue(ticks);
		}
	}

	@Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
	private void btb$exportPartial(boolean ignoreFrozenGame, CallbackInfoReturnable<Float> cir) {
		if (ExportManager.active()) {
			cir.setReturnValue(ExportManager.partialForFrame());
		}
	}
}
