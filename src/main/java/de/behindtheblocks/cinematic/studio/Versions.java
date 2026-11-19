package de.behindtheblocks.cinematic.studio;

import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * Automatic versions + undo for everything the mod writes into {@code config/btb_cinematic}
 * (scenes, camera paths, HUD texts/credits, dialog …).
 *
 * <p>Every save calls {@link #before(Path)} first: the old file is copied to
 * {@code config/btb_cinematic/.versionen/<folder>/<id>/<time>.json} (the newest 25 are kept) and
 * remembered on an undo stack. {@link #undo()} puts the previous state back.
 */
public final class Versions {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/versions");
	private static final int KEEP = 25;
	private static final int UNDO_DEPTH = 60;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

	/** file that changed + its copy from before (null: the file did not exist yet). */
	public record Change(Path file, Path before, String label) {
	}

	private static final Deque<Change> UNDO = new ArrayDeque<>();
	/** Set while undo/restore writes, so that write is not itself recorded. */
	private static boolean restoring;

	private Versions() {
	}

	public static Path configRoot() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID);
	}

	public static Path root() {
		return configRoot().resolve(".versionen");
	}

	/** "scenes/intro" for config/btb_cinematic/scenes/intro.json */
	public static String key(Path file) {
		Path rel;
		try {
			rel = configRoot().toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
		} catch (IllegalArgumentException e) {
			rel = file.getFileName();
		}
		String k = rel.toString().replace('\\', '/');
		return k.endsWith(".json") ? k.substring(0, k.length() - 5) : k;
	}

	/** Call right before a file is written or deleted. */
	public static synchronized void before(Path file) {
		if (restoring || file == null) {
			return;
		}
		String key = key(file);
		Path copy = null;
		try {
			if (Files.exists(file)) {
				Path dir = root().resolve(key);
				Files.createDirectories(dir);
				copy = dir.resolve(LocalDateTime.now().format(STAMP) + ".json");
				Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
				prune(dir);
			}
		} catch (IOException e) {
			LOG.warn("No version of {}: {}", key, e.toString());
		}
		// several saves of the same file within a moment (one click) are one undo step
		Change last = UNDO.peekFirst();
		if (last != null && last.file.equals(file) && copy != null && last.before != null
				&& System.currentTimeMillis() - lastAt < 400) {
			lastAt = System.currentTimeMillis();
			return;
		}
		lastAt = System.currentTimeMillis();
		UNDO.push(new Change(file, copy, key));
		while (UNDO.size() > UNDO_DEPTH) {
			UNDO.removeLast();
		}
	}

	private static long lastAt;

	private static void prune(Path dir) throws IOException {
		List<Path> all = list(dir);
		for (int i = 0; i < all.size() - KEEP; i++) {
			Files.deleteIfExists(all.get(i));
		}
	}

	private static List<Path> list(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Undoes the last change; returns what was undone (null: nothing to undo). */
	public static synchronized Change undo() throws IOException {
		Change c = UNDO.poll();
		if (c == null) {
			return null;
		}
		restoring = true;
		try {
			if (c.before == null) {
				Files.deleteIfExists(c.file);
			} else {
				Files.createDirectories(c.file.getParent());
				Files.copy(c.before, c.file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			restoring = false;
		}
		return c;
	}

	public static synchronized int undoSize() {
		return UNDO.size();
	}

	public static synchronized String nextUndo() {
		Change c = UNDO.peek();
		return c == null ? "" : c.label;
	}

	/** Saved versions of one file (oldest first), as "folder/id" key. */
	public static List<String> versions(String key) {
		List<String> out = new ArrayList<>();
		for (Path p : list(root().resolve(key))) {
			String n = p.getFileName().toString();
			out.add(n.substring(0, n.length() - 5));
		}
		return out;
	}

	/** All keys that have versions, newest change first. */
	public static List<String> keys() {
		List<String[]> found = new ArrayList<>();
		if (!Files.isDirectory(root())) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(root(), 3)) {
			s.filter(Files::isDirectory).forEach(d -> {
				List<Path> v = list(d);
				if (!v.isEmpty()) {
					found.add(new String[] {root().relativize(d).toString().replace('\\', '/'), v.get(v.size() - 1).getFileName().toString()});
				}
			});
		} catch (IOException e) {
			return List.of();
		}
		found.sort((a, b) -> b[1].compareTo(a[1]));
		return found.stream().map(a -> a[0]).toList();
	}

	/** Puts a stored version back (the current state becomes a version itself, so this can be undone). */
	public static void restore(String key, String version) throws IOException {
		Path src = root().resolve(key).resolve(version + ".json");
		if (!Files.exists(src)) {
			throw new IOException("Version " + version + " not found");
		}
		Path target = configRoot().resolve(key + ".json");
		before(target);
		restoring = true;
		try {
			Files.createDirectories(target.getParent());
			Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			restoring = false;
		}
	}
}
