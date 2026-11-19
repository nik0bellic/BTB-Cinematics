package de.behindtheblocks.cinematic.content;

import de.behindtheblocks.cinematic.sound.BtbSounds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Glutfackel (ember flare): a lighting tool for shots. Right-click where you look (up to 40 blocks):
 * a burning flare lies there for 45 s – real light (level 15), red smoke rising, sparks and embers.
 * Lights up dark corners of a set or marks a spot for the actors. Leaves nothing behind.
 */
public class EmberFlareItem extends Item {
	private record Flare(ServerLevel level, BlockPos pos, Vec3 at, int[] life) {
	}

	private static final List<Flare> FLARES = new ArrayList<>();
	private static final int LIFE = 45 * 20;

	public EmberFlareItem(Properties properties) {
		super(properties);
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Iterator<Flare> it = FLARES.iterator();
			while (it.hasNext()) {
				Flare f = it.next();
				int t = ++f.life()[0];
				if (t >= LIFE || !f.level().isLoaded(f.pos())) {
					if (f.level().getBlockState(f.pos()).is(Blocks.LIGHT)) {
						f.level().setBlock(f.pos(), Blocks.AIR.defaultBlockState(), 3);
					}
					it.remove();
					continue;
				}
				double fade = t > LIFE - 100 ? (LIFE - t) / 100.0 : 1;
				if (t % 3 == 0) {
					f.level().sendParticles(new DustParticleOptions(0xB01808, 1.1f), true, true, f.at().x, f.at().y + 0.3, f.at().z,
							(int) Math.ceil(1.5 * fade), 0.12, 0.2, 0.12, 0.01);
					f.level().sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, f.at().x, f.at().y + 0.4, f.at().z,
							1, 0.05, 0.1, 0.05, 0.015);
				}
				f.level().sendParticles(ParticleTypes.FLAME, true, true, f.at().x, f.at().y + 0.15, f.at().z, 1, 0.05, 0.05, 0.05, 0.01);
				if (t % 5 == 0) {
					f.level().sendParticles(ParticleTypes.LAVA, true, true, f.at().x, f.at().y + 0.2, f.at().z, 1, 0.05, 0.05, 0.05, 0);
				}
			}
		});
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel sl)) {
			return InteractionResult.SUCCESS;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(40));
		BlockHitResult hit = sl.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.MISS) {
			return InteractionResult.FAIL;
		}
		BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
		if (!sl.getBlockState(pos).isAir()) {
			return InteractionResult.FAIL;
		}
		sl.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
		FLARES.add(new Flare(sl, pos, Vec3.atBottomCenterOf(pos), new int[1]));
		sl.playSound(null, pos, BtbSounds.FLARE_IGNITE, SoundSource.BLOCKS, 1.6f, 1f);
		if (!player.getAbilities().instabuild) {
			player.getItemInHand(hand).shrink(1);
		}
		player.getCooldowns().addCooldown(player.getItemInHand(hand), 10);
		return InteractionResult.SUCCESS;
	}
}
