package de.behindtheblocks.cinematic.client.sound;

import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your own music: every {@code .ogg} in {@code config/btb_cinematic/music/} becomes the sound
 * {@code btb_music:<name>} (in scenes: {@code btb sound music musik.<name> 0 once}). MP3/WAV files are
 * converted to OGG with ffmpeg when it is installed. The files are put into a small resource pack
 * ({@code resourcepacks/btb_musik}) that is switched on automatically.
 */
public final class MusicPack {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/music");
	public static final String PACK_ID = "file/btb_musik";
	private static boolean changed;

	private MusicPack() {
	}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("music");
	}

	/** Original audio file of a track (for muxing into exports): .ogg, .mp3, .wav or .flac. */
	public static Path source(String name) {
		for (String ext : new String[] {".mp3", ".wav", ".flac", ".ogg"}) {
			Path p = folder().resolve(name + ext);
			if (Files.exists(p)) {
				return p;
			}
		}
		return null;
	}

	public static void init() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			try {
				if (build(mc)) {
					select(mc);
				}
			} catch (Exception e) {
				LOG.warn("Music pack not built: {}", e.toString());
			}
		});
	}

	private static String clean(String file) {
		String n = file.substring(0, file.lastIndexOf('.')).toLowerCase(Locale.ROOT);
		return n.replaceAll("[^a-z0-9_.-]", "_");
	}

	/** Writes the pack; true if there is any music. */
	static boolean build(Minecraft mc) throws IOException {
		Path in = folder();
		Files.createDirectories(in);
		List<Path> files;
		try (var s = Files.list(in)) {
			files = s.filter(p -> p.toString().matches("(?i).*\\.(ogg|mp3|wav|flac)$")).toList();
		}
		if (files.isEmpty()) {
			return false;
		}
		Path pack = mc.gameDirectory.toPath().resolve("resourcepacks").resolve("btb_musik");
		Path sounds = pack.resolve("assets/btb_music/sounds");
		Files.createDirectories(sounds);
		Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {\"description\": \"BTB Cinematic: eigene Musik aus config/btb_cinematic/music\","
				+ " \"pack_format\": 97, \"min_format\": 69, \"max_format\": 999}}", StandardCharsets.UTF_8);
		List<String> names = new ArrayList<>();
		for (Path f : files) {
			String name = clean(f.getFileName().toString());
			Path out = sounds.resolve(name + ".ogg");
			boolean ogg = f.toString().toLowerCase(Locale.ROOT).endsWith(".ogg");
			if (names.contains(name)) {
				continue;
			}
			if (Files.exists(out) && Files.getLastModifiedTime(out).compareTo(Files.getLastModifiedTime(f)) >= 0) {
				names.add(name);
				continue;
			}
			changed = true;
			if (ogg) {
				Files.copy(f, out, StandardCopyOption.REPLACE_EXISTING);
				names.add(name);
			} else if (convert(f, out)) {
				names.add(name);
			} else {
				LOG.warn("{}: Minecraft only plays OGG – install ffmpeg or convert it yourself", f.getFileName());
			}
		}
		StringBuilder json = new StringBuilder("{");
		for (int i = 0; i < names.size(); i++) {
			String n = names.get(i);
			json.append(i == 0 ? "" : ",").append("\n  \"").append(n).append("\": {\"sounds\": [{\"name\": \"btb_music:").append(n)
					.append("\", \"stream\": true}]}");
		}
		json.append("\n}\n");
		Files.writeString(pack.resolve("assets/btb_music/sounds.json"), json.toString(), StandardCharsets.UTF_8);
		LOG.info("Music pack: {}", names);
		return !names.isEmpty();
	}

	private static boolean convert(Path in, Path out) {
		String ffmpeg = de.behindtheblocks.cinematic.client.export.ExportManager.ffmpeg();
		if (ffmpeg == null) {
			return false;
		}
		try {
			Process p = new ProcessBuilder(ffmpeg, "-y", "-i", in.toString(), "-vn", "-c:a", "libvorbis", "-q:a", "7", out.toString())
					.redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor() == 0 && Files.exists(out);
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static void select(Minecraft mc) {
		var repo = mc.getResourcePackRepository();
		repo.reload();
		if (!repo.isAvailable(PACK_ID)) {
			return;
		}
		if (repo.getSelectedIds().contains(PACK_ID)) {
			if (changed) {
				mc.reloadResourcePacks(); // new or changed tracks
			}
			return;
		}
		List<String> sel = new ArrayList<>(repo.getSelectedIds());
		sel.add(PACK_ID);
		repo.setSelected(sel);
		mc.options.updateResourcePacks(repo);
		mc.options.save();
	}
}
