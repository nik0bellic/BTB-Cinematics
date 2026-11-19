package de.behindtheblocks.cinematic.client.model;

/** Optional Iris detection without a compile-time dependency (reflection, cached per second). */
public final class ShaderCompat {
	private static long checkedAt;
	private static boolean inUse;

	private ShaderCompat() {
	}

	public static boolean shaderPackInUse() {
		long now = de.behindtheblocks.cinematic.client.export.ExportClock.nowMs();
		if (now - checkedAt > 1000) {
			checkedAt = now;
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object inst = api.getMethod("getInstance").invoke(null);
				inUse = (Boolean) api.getMethod("isShaderPackInUse").invoke(inst);
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				inUse = false;
			}
		}
		return inUse;
	}
}
