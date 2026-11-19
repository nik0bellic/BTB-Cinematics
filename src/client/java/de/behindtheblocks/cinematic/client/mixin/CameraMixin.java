package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.camera.CameraPose;
import de.behindtheblocks.cinematic.client.camera.CinematicCameraController;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the camera after vanilla aligned it with the player.
 * Target: {@code Camera.alignWithEntity(float)} and {@code Camera.calculateFov(float)} (26.3).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;
	@Shadow
	private Vec3 position;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	@Final
	private static Vector3fc FORWARDS;
	@Shadow
	@Final
	private static Vector3fc UP;
	@Shadow
	@Final
	private static Vector3fc LEFT;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void btb$applyCinematicCamera(float partialTicks, CallbackInfo ci) {
		boolean shotActive = CinematicCameraController.isActive();
		CameraPose pose = CinematicCameraController.pose(partialTicks, position.x, position.y, position.z, yRot, xRot);
		if (!shotActive && pose.x() == position.x && pose.y() == position.y && pose.z() == position.z
				&& pose.yaw() == yRot && pose.pitch() == xRot && pose.roll() == 0) {
			return; // nothing to change this frame
		}
		setPosition(pose.x(), pose.y(), pose.z());
		setRotation(pose.yaw(), pose.pitch());
		if (pose.roll() != 0) {
			rotation.rotateZ((float) Math.toRadians(pose.roll()));
			FORWARDS.rotate(rotation, forwards);
			UP.rotate(rotation, up);
			LEFT.rotate(rotation, left);
		}
		if (shotActive) {
			// Detached: the local player's body is rendered (it is part of the shot) and no hand is drawn.
			detached = true;
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void btb$cinematicFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float fov = CinematicCameraController.fov(cir.getReturnValueF(), partialTicks);
		if (fov != cir.getReturnValueF()) {
			cir.setReturnValue(fov);
		}
	}
}
