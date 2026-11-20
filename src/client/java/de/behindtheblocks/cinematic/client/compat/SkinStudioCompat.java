package de.behindtheblocks.cinematic.client.compat;

import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works together with BTB Skin Studio (optional, found at runtime).
 *
 * <p>Skin Studio dresses <em>players</em> (by their UUID): chosen skin, cosmetics, body proportions,
 * emotes. Replay puppets, doubles and statists are mannequins that carry an actor's profile – here
 * they get the same look as that actor, and they can play Skin Studio emotes (recorded emotes in
 * replays, or {@code /btb emote}).
 */
public final class SkinStudioCompat {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/skinstudio");
	private static boolean ready;
	private static Method override, activeFor, bodyFor, pose, item;
	private static Method bodyHeight, bodyHead, bodyArms, bodyLegs;
	private static Object kCosmetics, kBody, kEmote, kPlayer;
	private static Method setData;
	private static Map<UUID, Object> playing;
	private static Constructor<?> playingCtor;
	/** Puppets that wear the look stored in their take (keyed by the puppet itself, not by the actor). */
	private static final java.util.Set<UUID> STORED_LOOK = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private SkinStudioCompat() {
	}

	public static boolean present() {
		return ready;
	}

	public static void init() {
		ClientCueDispatcher.register("btb_puppet_emote", (scene, tick, data) -> {
			if (ready) {
				startEmote(UUID.fromString(data.get("entity").getAsString()), data.get("emote").getAsString());
			}
		});
		ClientCueDispatcher.register("btb_puppet_look", (scene, tick, data) -> STORED_LOOK.add(UUID.fromString(data.get("entity").getAsString())));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> STORED_LOOK.clear());
		if (!FabricLoader.getInstance().isModLoaded("btb_skinstudio")) {
			return;
		}
		try {
			String p = "de.behindtheblocks.skinstudio.client.";
			Class<?> skins = Class.forName(p + "skin.ClientSkins");
			Class<?> sync = Class.forName(p + "net.ClientCosmeticSync");
			Class<?> emotes = Class.forName(p + "emote.ClientEmotes");
			Class<?> data = Class.forName(p + "render.CosmeticRenderData");
			override = skins.getMethod("override", UUID.class, PlayerSkin.class);
			activeFor = sync.getMethod("activeFor", UUID.class);
			bodyFor = sync.getMethod("bodyFor", UUID.class);
			pose = emotes.getMethod("pose", UUID.class);
			item = emotes.getMethod("item", UUID.class);
			kCosmetics = data.getField("COSMETICS").get(null);
			kBody = data.getField("BODY").get(null);
			kEmote = data.getField("EMOTE").get(null);
			kPlayer = data.getField("PLAYER").get(null);
			for (Method m : AvatarRenderState.class.getMethods()) {
				if (m.getName().equals("setData") && m.getParameterCount() == 2) {
					setData = m;
				}
			}
			Field f = emotes.getDeclaredField("PLAYING");
			f.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<UUID, Object> map = (Map<UUID, Object>) f.get(null);
			playing = map;
			for (Class<?> inner : emotes.getDeclaredClasses()) {
				if (inner.getSimpleName().equals("Playing")) {
					playingCtor = inner.getDeclaredConstructor(String.class, long.class);
					playingCtor.setAccessible(true);
				}
			}
			ready = setData != null;
			LOG.info("BTB Skin Studio found: replay puppets and statists show Skin Studio skins, cosmetics and emotes");
		} catch (ReflectiveOperationException | RuntimeException e) {
			ready = false;
			LOG.warn("BTB Skin Studio found, but its interface changed – puppets keep vanilla skins ({})", e.toString());
		}
	}

	/** The actor a mannequin stands in for (its profile id), or null. */
	private static UUID actorOf(Mannequin m) {
		try {
			var prof = m.getProfile();
			if (prof == null || prof.partialProfile() == null) {
				return null;
			}
			var gp = prof.partialProfile();
			UUID id = gp.id();
			// statists made from a player name only: find that player by name
			if ((id == null || id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) && gp.name() != null && !gp.name().isEmpty()) {
				var conn = Minecraft.getInstance().getConnection();
				var info = conn == null ? null : conn.getPlayerInfo(gp.name());
				return info == null ? null : info.getProfile().id();
			}
			return id;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Called while the mannequin's render state is filled (after Skin Studio's own code). */
	public static void dress(Mannequin m, AvatarRenderState state) {
		if (!ready) {
			return;
		}
		// the look of the moment of recording (stored in the take) – otherwise the actor's current look
		UUID actor = STORED_LOOK.contains(m.getUUID()) ? m.getUUID() : actorOf(m);
		try {
			if (actor != null) {
				PlayerSkin skin = (PlayerSkin) override.invoke(null, actor, state.skin);
				if (skin != null) {
					state.skin = skin;
				}
				setData.invoke(state, kCosmetics, activeFor.invoke(null, actor));
				setData.invoke(state, kPlayer, actor);
				Object body = bodyFor.invoke(null, actor);
				if (body != null) {
					if (bodyHeight == null) {
						bodyHeight = body.getClass().getMethod("height");
						bodyHead = body.getClass().getMethod("head");
						bodyArms = body.getClass().getMethod("arms");
						bodyLegs = body.getClass().getMethod("legs");
					}
					float h = (float) bodyHeight.invoke(body);
					setData.invoke(state, kBody, new float[] {h, (float) bodyHead.invoke(body), (float) bodyArms.invoke(body), (float) bodyLegs.invoke(body)});
					state.scale *= h;
				}
			}
			// emotes are played on the mannequin itself (recorded in the replay or /btb emote)
			float[] p = (float[]) pose.invoke(null, m.getUUID());
			if (p != null) {
				setData.invoke(state, kEmote, p);
				ItemStack held = (ItemStack) item.invoke(null, m.getUUID());
				if (held != null) {
					state.rightHandItemStack = held;
					Minecraft.getInstance().getItemModelResolver().updateForLiving(state.rightHandItemState, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, m);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			ready = false;
			LOG.warn("Skin Studio look for puppets switched off: {}", e.toString());
		}
	}

	/** Starts (or with "" stops) a Skin Studio emote on any entity (replay puppet, statist). */
	public static void startEmote(UUID entity, String emote) {
		if (playing == null || playingCtor == null) {
			return;
		}
		try {
			if (emote.isEmpty()) {
				playing.remove(entity);
			} else {
				playing.put(entity, playingCtor.newInstance(emote, System.nanoTime()));
			}
		} catch (ReflectiveOperationException e) {
			LOG.warn("Emote not started: {}", e.toString());
		}
	}

	/** Skin Studio opens its wardrobe with J – the replay editor's camera key moves to H then. */
	public static boolean wantsKeyJ() {
		return FabricLoader.getInstance().isModLoaded("btb_skinstudio");
	}

	@SuppressWarnings("unused")
	private static List<?> none() {
		return List.of();
	}
}
