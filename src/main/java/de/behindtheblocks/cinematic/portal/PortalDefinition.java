package de.behindtheblocks.cinematic.portal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.content.PortalStyle;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Portal configuration ({@code config/btb_cinematic/portals/<id>.json}).
 *
 * <p>The surface is either a rectangle ({@code from}/{@code to}) or any shape given as
 * {@code cells} relative to {@code origin} (created by {@code /btb portal fill <id> <inside>},
 * which flood-fills the inside of an existing frame — circles, arches, stars, floor portals ...).
 *
 * <pre>{@code
 * {
 *   "dimension": "minecraft:overworld",
 *   "from": [10, -60, 0], "to": [12, -57, 0],          // rectangle …
 *   "origin": [10, -60, 0], "axis": "x", "cells": [[0,0,0],[1,0,0], …],   // … or any shape
 *   "style": "white",                                    // nether | white
 *   "facing": "south",                                   // side players exit towards (up/down for floor portals)
 *   "keystone": [11, -61, 0], "require_key": true, "charge_ticks": 120, "settle_ticks": 20,
 *   "link": "portal_b",  |  "exit": {"dimension": "...", "pos": [x,y,z], "yaw": 0, "pitch": 0},
 *   "transition": {"color": "white", "in": 10, "hold": 16, "out": 30},
 *   "arrival_scene": "", "activation_scene": ""
 * }
 * }</pre>
 */
public record PortalDefinition(String id, Identifier dimension, Set<BlockPos> cells, BlockPos min, BlockPos max, Direction.Axis axis,
		Direction facing, PortalStyle style, BlockPos keystone, boolean requireKey, int chargeTicks, int settleTicks, String link,
		Exit exit, int fadeColor, int fadeIn, int fadeHold, int fadeOut, String arrivalScene, String activationScene) {

	/** Large film sets are supported: up to 8192 surface blocks, e.g. 128 wide x 64 high. */
	public static final int MAX_SURFACE = 8192;

	public record Exit(Identifier dimension, double x, double y, double z, float yaw, float pitch) {
	}

	public static PortalDefinition parse(String id, JsonObject json) {
		JsonParams p = new JsonParams(json);
		Identifier dim = Identifier.tryParse(p.getString("dimension", "minecraft:overworld"));
		Set<BlockPos> cells = new LinkedHashSet<>();
		Direction.Axis axis;
		if (p.has("cells")) {
			double[] o = p.requireDoubleArray("origin", 3);
			BlockPos origin = BlockPos.containing(o[0], o[1], o[2]);
			for (JsonElement e : json.getAsJsonArray("cells")) {
				JsonArray c = e.getAsJsonArray();
				cells.add(origin.offset(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()));
			}
			axis = Direction.Axis.byName(p.getString("axis", "x"));
			if (axis == null) {
				throw new JsonParamException("'axis' must be x, y or z");
			}
		} else {
			double[] a = p.requireDoubleArray("from", 3), b = p.requireDoubleArray("to", 3);
			BlockPos lo = BlockPos.containing(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]));
			BlockPos hi = BlockPos.containing(Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
			int sx = hi.getX() - lo.getX() + 1, sy = hi.getY() - lo.getY() + 1, sz = hi.getZ() - lo.getZ() + 1;
			if (sz == 1) {
				axis = Direction.Axis.X; // surface spans X/Y
			} else if (sx == 1) {
				axis = Direction.Axis.Z; // surface spans Z/Y
			} else if (sy == 1) {
				axis = Direction.Axis.Y; // floor portal spans X/Z
			} else {
				throw new JsonParamException("portal surface must be one block thick");
			}
			if ((long) sx * sy * sz > MAX_SURFACE) {
				throw new JsonParamException("portal surface larger than " + MAX_SURFACE + " blocks");
			}
			BlockPos.betweenClosed(lo, hi).forEach(c -> cells.add(c.immutable()));
		}
		if (cells.isEmpty() || cells.size() > MAX_SURFACE) {
			throw new JsonParamException("portal needs 1.." + MAX_SURFACE + " surface blocks");
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE,
				maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos c : cells) {
			minX = Math.min(minX, c.getX());
			minY = Math.min(minY, c.getY());
			minZ = Math.min(minZ, c.getZ());
			maxX = Math.max(maxX, c.getX());
			maxY = Math.max(maxY, c.getY());
			maxZ = Math.max(maxZ, c.getZ());
		}
		String defFacing = switch (axis) {
			case X -> "south";
			case Z -> "east";
			case Y -> "up";
		};
		Direction facing = Direction.byName(p.getString("facing", defFacing));
		if (facing == null || facing.getAxis() == axis && axis != Direction.Axis.Y
				|| axis == Direction.Axis.Y && !facing.getAxis().isVertical()
				|| axis != Direction.Axis.Y && facing.getAxis().isVertical()) {
			throw new JsonParamException("'facing' must point out of the surface");
		}
		PortalStyle style = PortalStyle.byName(p.getString("style", "nether"));
		if (style == null) {
			throw new JsonParamException("'style' must be nether or white");
		}
		BlockPos keystone = null;
		if (p.has("keystone")) {
			double[] k = p.requireDoubleArray("keystone", 3);
			keystone = BlockPos.containing(k[0], k[1], k[2]);
		}
		Exit exit = null;
		if (p.has("exit")) {
			JsonParams e = new JsonParams(p.getObject("exit").orElseThrow());
			double[] pos = e.requireDoubleArray("pos", 3);
			exit = new Exit(Identifier.tryParse(e.getString("dimension", dim.toString())), pos[0], pos[1], pos[2],
					e.getFloat("yaw", 0), e.getFloat("pitch", 0));
		}
		JsonParams t = new JsonParams(p.getObject("transition").orElse(new JsonObject()));
		int charge = p.getInt("charge_ticks", 120);
		if (charge < 0 || charge > 2400) {
			throw new JsonParamException("'charge_ticks' must be 0..2400");
		}
		return new PortalDefinition(id, dim, Collections.unmodifiableSet(cells), new BlockPos(minX, minY, minZ),
				new BlockPos(maxX, maxY, maxZ), axis, facing, style, keystone, p.getBoolean("require_key", true), charge,
				p.getInt("settle_ticks", 20), p.getString("link", ""), exit,
				Colors.parse(t.getString("color", "white")), Math.max(1, t.getInt("in", 10)), Math.max(0, t.getInt("hold", 16)),
				Math.max(1, t.getInt("out", 30)), p.getString("arrival_scene", ""), p.getString("activation_scene", ""));
	}

	public boolean contains(BlockPos pos) {
		return cells.contains(pos);
	}

	public int surfaceBlocks() {
		return cells.size();
	}

	/** Half the larger surface extent — used to scale effects to the portal size. */
	public double radius() {
		return Math.max(max.getX() - min.getX() + 1, Math.max(max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1)) / 2.0;
	}

	public double centerX() {
		return cells.stream().mapToDouble(c -> c.getX() + 0.5).average().orElse(min.getX());
	}

	public double centerY() {
		return cells.stream().mapToDouble(c -> c.getY() + 0.5).average().orElse(min.getY());
	}

	public double centerZ() {
		return cells.stream().mapToDouble(c -> c.getZ() + 0.5).average().orElse(min.getZ());
	}

	public static JsonObject template(String dimension, BlockPos a, BlockPos b) {
		JsonObject o = base(dimension);
		o.add("from", arr(a));
		o.add("to", arr(b));
		return o;
	}

	/** Free-form surface (flood-filled cells). */
	public static JsonObject template(String dimension, Collection<BlockPos> cells, Direction.Axis axis) {
		JsonObject o = base(dimension);
		BlockPos origin = cells.iterator().next();
		o.add("origin", arr(origin));
		o.addProperty("axis", axis.getName());
		JsonArray list = new JsonArray();
		for (BlockPos c : cells) {
			JsonArray r = new JsonArray();
			r.add(c.getX() - origin.getX());
			r.add(c.getY() - origin.getY());
			r.add(c.getZ() - origin.getZ());
			list.add(r);
		}
		o.add("cells", list);
		return o;
	}

	private static JsonObject base(String dimension) {
		JsonObject o = new JsonObject();
		o.addProperty("dimension", dimension);
		o.addProperty("style", "nether");
		o.addProperty("require_key", true);
		o.addProperty("charge_ticks", 120);
		JsonObject t = new JsonObject();
		t.addProperty("color", "white");
		t.addProperty("in", 10);
		t.addProperty("hold", 16);
		t.addProperty("out", 30);
		o.add("transition", t);
		return o;
	}

	static JsonArray arr(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}
}
