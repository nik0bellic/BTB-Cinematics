package de.behindtheblocks.cinematic.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact copy of a cuboid of blocks, including block entity data (chest contents, signs, ...),
 * stored palette-compressed. Used to make destruction and other world changes fully reversible.
 */
public final class BlockSnapshot {
	/** Flags for silent, drop-free changes: clients update, no shape updates, no drops, no side effects. */
	public static final int SILENT = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	private static final int RESTORE = SILENT | Block.UPDATE_SKIP_ON_PLACE;

	private final BlockPos min;
	private final BlockPos max;
	private final List<BlockState> palette;
	private final int[] indices;
	private final Map<BlockPos, CompoundTag> blockEntities;

	private BlockSnapshot(BlockPos min, BlockPos max, List<BlockState> palette, int[] indices, Map<BlockPos, CompoundTag> blockEntities) {
		this.min = min;
		this.max = max;
		this.palette = palette;
		this.indices = indices;
		this.blockEntities = blockEntities;
	}

	public static BlockSnapshot capture(ServerLevel level, BlockPos min, BlockPos max) {
		Map<BlockState, Integer> lookup = new HashMap<>();
		List<BlockState> palette = new ArrayList<>();
		int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
		int[] indices = new int[sx * sy * sz];
		Map<BlockPos, CompoundTag> bes = new HashMap<>();
		int i = 0;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					BlockPos p = min.offset(x, y, z);
					BlockState s = level.getBlockState(p);
					indices[i++] = lookup.computeIfAbsent(s, k -> {
						palette.add(k);
						return palette.size() - 1;
					});
					BlockEntity be = level.getBlockEntity(p);
					if (be != null) {
						bes.put(p.immutable(), be.saveWithFullMetadata(level.registryAccess()));
					}
				}
			}
		}
		return new BlockSnapshot(min, max, palette, indices, bes);
	}

	/**
	 * Writes the snapshot back. Changes only blocks that differ, so restoring an untouched area is
	 * cheap. Returns the number of changed blocks.
	 */
	public int restore(ServerLevel level) {
		int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
		int changed = 0, i = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					BlockState want = palette.get(indices[i++]);
					p.set(min.getX() + x, min.getY() + y, min.getZ() + z);
					if (level.getBlockState(p) != want) {
						level.setBlock(p, want, RESTORE);
						changed++;
					}
				}
			}
		}
		for (Map.Entry<BlockPos, CompoundTag> e : blockEntities.entrySet()) {
			BlockEntity loaded = BlockEntity.loadStatic(e.getKey(), level.getBlockState(e.getKey()), e.getValue(), level.registryAccess());
			if (loaded != null) {
				level.setBlockEntity(loaded);
			}
		}
		return changed;
	}

	public BlockState stateAt(BlockPos pos) {
		int sx = max.getX() - min.getX() + 1, sz = max.getZ() - min.getZ() + 1;
		int x = pos.getX() - min.getX(), y = pos.getY() - min.getY(), z = pos.getZ() - min.getZ();
		return palette.get(indices[(y * sz + z) * sx + x]);
	}

	public int volume() {
		return indices.length;
	}

	// ------------------------------------------------------------------ persistence

	public void save(Path file) throws IOException {
		CompoundTag root = new CompoundTag();
		root.put("min", new IntArrayTag(new int[] {min.getX(), min.getY(), min.getZ()}));
		root.put("max", new IntArrayTag(new int[] {max.getX(), max.getY(), max.getZ()}));
		ListTag pal = new ListTag();
		palette.forEach(s -> pal.add(NbtUtils.writeBlockState(s)));
		root.put("palette", pal);
		root.put("blocks", new IntArrayTag(indices));
		ListTag bes = new ListTag();
		blockEntities.forEach((pos, tag) -> {
			CompoundTag t = new CompoundTag();
			t.put("pos", new IntArrayTag(new int[] {pos.getX(), pos.getY(), pos.getZ()}));
			t.put("data", tag);
			bes.add(t);
		});
		root.put("block_entities", bes);
		Files.createDirectories(file.getParent());
		NbtIo.writeCompressed(root, file);
	}

	public static BlockSnapshot load(ServerLevel level, Path file) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		int[] a = root.getIntArray("min").orElseThrow(), b = root.getIntArray("max").orElseThrow();
		HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		List<BlockState> palette = new ArrayList<>();
		for (Tag t : root.getListOrEmpty("palette")) {
			palette.add(NbtUtils.readBlockState(blocks, (CompoundTag) t));
		}
		Map<BlockPos, CompoundTag> bes = new HashMap<>();
		for (Tag t : root.getListOrEmpty("block_entities")) {
			CompoundTag c = (CompoundTag) t;
			int[] p = c.getIntArray("pos").orElseThrow();
			bes.put(new BlockPos(p[0], p[1], p[2]), c.getCompoundOrEmpty("data"));
		}
		return new BlockSnapshot(new BlockPos(a[0], a[1], a[2]), new BlockPos(b[0], b[1], b[2]), palette,
				root.getIntArray("blocks").orElseThrow(), bes);
	}
}
