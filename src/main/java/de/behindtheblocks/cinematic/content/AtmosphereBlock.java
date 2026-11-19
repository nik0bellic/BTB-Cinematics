package de.behindtheblocks.cinematic.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Set-dressing blocks for atmosphere: each one has its own model, light and client-side particles
 * (no server cost, nothing to configure – place it and it lives).
 */
public class AtmosphereBlock extends Block {
	public enum Fx {
		/** Glutkessel: fire, rising sparks and smoke, the odd ember popping out. */
		BRAZIER,
		/** Kettenleuchter: small candle flames on the ring, a thin wisp of smoke. */
		CHANDELIER,
		/** Lichtsäule: a column of light with dust slowly drifting through it. */
		SHAFT,
		/** Glutriss: embers and heat rising from glowing cracks in the floor. */
		EMBERS,
		/** Nebelquelle: low, creeping ground fog. */
		FOG,
		/** Seelenkristall-Leuchte: cold sparkles and drifting souls. */
		CRYSTAL
	}

	/** Brightness 0..15 (dimmable with /btb licht); particles thin out with it. */
	public static final net.minecraft.world.level.block.state.properties.IntegerProperty POWER =
			net.minecraft.world.level.block.state.properties.IntegerProperty.create("power", 0, 15);

	private final Fx fx;
	private final VoxelShape shape;
	private final boolean solid;

	public AtmosphereBlock(Properties properties, Fx fx, VoxelShape shape, boolean solid) {
		super(properties);
		this.fx = fx;
		this.shape = shape;
		this.solid = solid;
		registerDefaultState(stateDefinition.any().setValue(POWER, 15));
	}

	@Override
	protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWER);
	}

	/** Light of a lamp at full power scaled by its current power. */
	public static int light(BlockState s, int full) {
		return Math.round(full * s.getValue(POWER) / 15f);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return solid ? shape : Shapes.empty();
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
		int power = state.getValue(POWER);
		if (power == 0 || r.nextInt(15) >= power) {
			return; // dimmed lamps calm down
		}
		double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;
		switch (fx) {
			case BRAZIER -> {
				for (int i = 0; i < 3; i++) {
					level.addParticle(ParticleTypes.FLAME, x + (r.nextDouble() - 0.5) * 0.5, y + 0.95, z + (r.nextDouble() - 0.5) * 0.5,
							0, 0.02 + r.nextDouble() * 0.03, 0);
				}
				level.addParticle(ParticleTypes.SMOKE, x + (r.nextDouble() - 0.5) * 0.3, y + 1.3, z + (r.nextDouble() - 0.5) * 0.3, 0, 0.05, 0);
				if (r.nextInt(3) == 0) {
					level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 1.4, z, (r.nextDouble() - 0.5) * 0.01, 0.04, (r.nextDouble() - 0.5) * 0.01);
				}
				if (r.nextInt(4) == 0) {
					level.addParticle(ParticleTypes.LAVA, x, y + 1.0, z, 0, 0, 0);
				}
			}
			case CHANDELIER -> {
				for (int i = 0; i < 6; i++) {
					double a = i * Math.PI / 3;
					if (r.nextInt(3) == 0) {
						level.addParticle(ParticleTypes.SMALL_FLAME, x + Math.cos(a) * 0.38, y + 0.62, z + Math.sin(a) * 0.38, 0, 0.002, 0);
					}
				}
				if (r.nextInt(5) == 0) {
					level.addParticle(ParticleTypes.SMOKE, x, y + 0.7, z, 0, 0.02, 0);
				}
			}
			case SHAFT -> {
				if (r.nextInt(2) == 0) {
					level.addParticle(ParticleTypes.WHITE_ASH, x + (r.nextDouble() - 0.5) * 0.7, y + r.nextDouble(), z + (r.nextDouble() - 0.5) * 0.7,
							0, -0.005, 0);
				}
				if (r.nextInt(12) == 0) {
					level.addParticle(ParticleTypes.END_ROD, x + (r.nextDouble() - 0.5) * 0.5, y + r.nextDouble(), z + (r.nextDouble() - 0.5) * 0.5,
							0, -0.004, 0);
				}
			}
			case EMBERS -> {
				if (r.nextInt(2) == 0) {
					level.addParticle(ParticleTypes.SMALL_FLAME, x + (r.nextDouble() - 0.5) * 0.9, y + 1.02, z + (r.nextDouble() - 0.5) * 0.9,
							(r.nextDouble() - 0.5) * 0.01, 0.03 + r.nextDouble() * 0.03, (r.nextDouble() - 0.5) * 0.01);
				}
				if (r.nextInt(6) == 0) {
					level.addParticle(ParticleTypes.WHITE_ASH, x, y + 1.2, z, 0, 0.02, 0);
				}
			}
			case FOG -> {
				for (int i = 0; i < 2; i++) {
					double ang = r.nextDouble() * Math.PI * 2, sp = 0.01 + r.nextDouble() * 0.02;
					level.addParticle(new DustParticleOptions(0x8C8C96, 3.0f + r.nextFloat() * 1.5f), x + (r.nextDouble() - 0.5) * 2,
							y + 0.15 + r.nextDouble() * 0.3, z + (r.nextDouble() - 0.5) * 2, Math.cos(ang) * sp, 0, Math.sin(ang) * sp);
				}
				if (r.nextInt(3) == 0) {
					level.addParticle(ParticleTypes.WHITE_SMOKE, x + (r.nextDouble() - 0.5) * 1.5, y + 0.2, z + (r.nextDouble() - 0.5) * 1.5,
							(r.nextDouble() - 0.5) * 0.02, 0.002, (r.nextDouble() - 0.5) * 0.02);
				}
			}
			case CRYSTAL -> {
				if (r.nextInt(3) == 0) {
					level.addParticle(ParticleTypes.END_ROD, x + (r.nextDouble() - 0.5) * 0.6, y + 0.3 + r.nextDouble() * 0.6,
							z + (r.nextDouble() - 0.5) * 0.6, 0, 0.004, 0);
				}
				if (r.nextInt(8) == 0) {
					level.addParticle(ParticleTypes.SOUL, x, y + 0.9, z, 0, 0.02, 0);
				}
			}
		}
	}
}
