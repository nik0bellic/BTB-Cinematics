package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.director.CrewMarkers;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wind: every particle (ash, smoke, sparks, flames) drifts the same way. Target: {@code Particle.tick()} (26.3). */
@Mixin(Particle.class)
public abstract class ParticleWindMixin {
	@Shadow
	protected double xd;
	@Shadow
	protected double zd;
	@Shadow
	protected boolean onGround;

	@Inject(method = "tick", at = @At("TAIL"))
	private void btb$wind(CallbackInfo ci) {
		double wx = CrewMarkers.windX, wz = CrewMarkers.windZ;
		if ((wx != 0 || wz != 0) && !onGround) {
			xd += wx;
			zd += wz;
		}
	}
}
