package de.behindtheblocks.cinematic.content.entity;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import de.behindtheblocks.cinematic.content.BtbItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Der Herzwächter: the boss guarding the Heart of the Nether. A towering blackstone colossus with a
 * glowing heart in its chest (own GeckoLib model + idle/walk/attack animations). Slow, heavy hits,
 * immune to fire and lava; drops the Nether key when defeated. No name tag needed – the look says it.
 */
public class HeartWarden extends Monster implements GeoEntity {
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
	private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("attack");
	private static final RawAnimation EMERGE = RawAnimation.begin().thenPlayAndHold("emerge");
	private static final RawAnimation SLAM = RawAnimation.begin().thenPlay("slam");
	private static final RawAnimation THROW = RawAnimation.begin().thenPlay("throw");
	private static final RawAnimation ROAR = RawAnimation.begin().thenPlay("roar");
	/** True while it climbs out of the ground (synced so the client plays the emerge animation from frame 0). */
	private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> EMERGING = net.minecraft.network.syncher.SynchedEntityData
			.defineId(HeartWarden.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
	private static final String EMERGED_TAG = "btb_emerged";
	private static final int EMERGE_TICKS = 60;
	private int emergeTicks;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public HeartWarden(EntityType<? extends Monster> type, Level level) {
		super(type, level);
		this.xpReward = 50;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
				.add(Attributes.MAX_HEALTH, 220)
				.add(Attributes.ATTACK_DAMAGE, 11)
				.add(Attributes.ATTACK_KNOCKBACK, 1.4)
				.add(Attributes.MOVEMENT_SPEED, 0.24)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.85)
				.add(Attributes.FOLLOW_RANGE, 40)
				.add(Attributes.ARMOR, 10);
	}

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		// true from the very first frame: the client never sees it standing before it breaks out of the ground
		builder.define(EMERGING, true);
	}

	public boolean isEmerging() {
		return entityData.get(EMERGING);
	}

	@Override
	public void tick() {
		if (level() instanceof ServerLevel sl) {
			if (entityTags().contains(EMERGED_TAG) && emergeTicks == 0 && isEmerging()) {
				entityData.set(EMERGING, false); // loaded from the world: already standing
			}
			if (tickCount == 1 && !entityTags().contains(EMERGED_TAG)) {
				// first appearance: it breaks out of the ground instead of just standing there
				addTag(EMERGED_TAG);
				entityData.set(EMERGING, true);
				emergeTicks = EMERGE_TICKS;
				sl.playSound(null, blockPosition(), SoundEvents.WARDEN_EMERGE, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.7f);
				sl.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.HOSTILE, 1.2f, 0.5f);
			}
			if (emergeTicks > 0) {
				emergeTicks--;
				var ground = sl.getBlockState(blockPosition().below());
				if (ground.isAir()) {
					ground = net.minecraft.world.level.block.Blocks.BLACKSTONE.defaultBlockState();
				}
				sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground), getX(), getY() + 0.1, getZ(),
						8, 0.9, 0.1, 0.9, 0.15);
				if (emergeTicks % 3 == 0) {
					sl.sendParticles(ParticleTypes.LAVA, getX(), getY() + 0.2, getZ(), 2, 0.8, 0.1, 0.8, 0.1);
					sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.4, getZ(), 3, 1.0, 0.3, 1.0, 0.02);
				}
				if (emergeTicks == 12) {
					sl.playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.6f);
					sl.sendParticles(ParticleTypes.FLAME, getX(), getY() + 2.3, getZ(), 40, 0.4, 0.4, 0.4, 0.08);
				}
				if (emergeTicks == 0) {
					entityData.set(EMERGING, false);
				}
			}
		}
		super.tick();
	}

	@Override
	protected boolean isImmobile() {
		return isEmerging() || super.isImmobile(); // no AI, no movement until it stands
	}


	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
		this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7));
		this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 16));
		this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
	}

	@Override
	public boolean doHurtTarget(ServerLevel level, Entity target) {
		triggerAnim("main", "attack");
		level.sendParticles(ParticleTypes.LAVA, getX(), getY() + 2, getZ(), 6, 0.6, 0.4, 0.6, 0.2);
		return super.doHurtTarget(level, target);
	}

	// ------------------------------------------------------------------ boss fight: three phases, telegraphed attacks

	private enum Attack { NONE, ROAR, SLAM, PILLARS, THROW }

	/** 1 = heavy fists, 2 (below half health) = the heart breaks open, lava pillars, 3 (below a quarter) = throws rocks. */
	private int phase = 1;
	private Attack attack = Attack.NONE;
	private int attackT, cooldown = 60;
	private final java.util.List<net.minecraft.world.phys.Vec3> marks = new java.util.ArrayList<>();
	private final java.util.Set<java.util.UUID> hit = new java.util.HashSet<>();
	private net.minecraft.world.entity.Display.BlockDisplay rock;
	private net.minecraft.world.phys.Vec3 rockFrom, rockTo;
	/** Running shockwaves and ground cracks: {centre, radius-or-progress, kind} handled per tick. */
	private final java.util.List<Object[]> waves = new java.util.ArrayList<>();

	public int phase() {
		return phase;
	}

	private java.util.List<net.minecraft.server.level.ServerPlayer> playersNear(ServerLevel sl, double r) {
		return sl.players().stream().filter(p -> !p.isSpectator() && p.distanceTo(this) < r).toList();
	}

	private void screenHit(ServerLevel sl, float shake, int flashColor, float flash) {
		var near = playersNear(sl, 40);
		de.behindtheblocks.cinematic.BtbCinematic.engine().module(de.behindtheblocks.cinematic.camera.CameraManager.class)
				.ifPresent(c -> c.shake(de.behindtheblocks.cinematic.timeline.TimelineManager.MANUAL, 0, shake, 18, 1.4f, near));
		if (flash > 0) {
			de.behindtheblocks.cinematic.BtbCinematic.engine().module(de.behindtheblocks.cinematic.effects.EffectManager.class)
					.ifPresent(fx -> fx.send(de.behindtheblocks.cinematic.timeline.TimelineManager.MANUAL, 0,
							new de.behindtheblocks.cinematic.effects.EffectSpec(de.behindtheblocks.cinematic.effects.EffectSpec.Kind.FLASH,
									flashColor, flashColor, flash, 1, 0, 12, ""), near));
		}
	}

	private void damage(ServerLevel sl, net.minecraft.server.level.ServerPlayer p, float amount, net.minecraft.world.phys.Vec3 from, double push, double up) {
		if (puppet()) {
			return; // replays only show the attack
		}
		if (p.isCreative()) {
			// creative actors are not hurt, but still thrown – the shot looks the same
		} else {
			p.hurtServer(sl, damageSources().mobAttack(this), amount);
		}
		net.minecraft.world.phys.Vec3 d = p.position().subtract(from);
		d = new net.minecraft.world.phys.Vec3(d.x, 0, d.z);
		if (d.lengthSqr() > 1e-4) {
			d = d.normalize();
		}
		p.setDeltaMovement(d.x * push, up, d.z * push);
		p.needsSync = true;
	}

	/** Replay: a puppet guardian performs a recorded attack (visuals only, nobody gets hurt). */
	public void playAttack(String name) {
		try {
			Attack a = Attack.valueOf(name);
			if (a == Attack.ROAR) {
				phase++;
			}
			start(a);
		} catch (IllegalArgumentException ignored) {
			// unknown attack in an old recording
		}
	}

	private boolean puppet() {
		return de.behindtheblocks.cinematic.replay.ReplayManager.isPuppet(this);
	}

	private void tickFight(ServerLevel sl) {
		tickWaves(sl);
		if (isEmerging() || !isAlive()) {
			return;
		}
		if (attack != Attack.NONE && (isNoAi() || puppet())) {
			attackT++; // a puppet still performs the attack it was told to do
			switch (attack) {
				case ROAR -> tickRoar(sl);
				case SLAM -> tickSlam(sl);
				case PILLARS -> tickPillars(sl);
				case THROW -> tickThrow(sl);
				default -> { }
			}
			return;
		}
		if (isNoAi() || puppet()) {
			return;
		}
		float hp = getHealth() / getMaxHealth();
		if (attack == Attack.NONE && ((phase == 1 && hp < 0.5f) || (phase == 2 && hp < 0.25f))) {
			phase++;
			start(Attack.ROAR);
			return;
		}
		if (attack == Attack.NONE) {
			var target = getTarget();
			if (--cooldown > 0 || target == null || target.distanceTo(this) > 22) {
				return;
			}
			double r = random.nextDouble();
			Attack next = phase >= 3 && r < 0.4 ? Attack.THROW : phase >= 2 && r < 0.75 ? Attack.PILLARS : Attack.SLAM;
			start(next);
			return;
		}
		attackT++;
		getNavigation().stop();
		if (getTarget() != null) {
			getLookControl().setLookAt(getTarget(), 30, 30);
		}
		switch (attack) {
			case ROAR -> tickRoar(sl);
			case SLAM -> tickSlam(sl);
			case PILLARS -> tickPillars(sl);
			case THROW -> tickThrow(sl);
			default -> { }
		}
	}

	private void start(Attack a) {
		de.behindtheblocks.cinematic.replay.ReplayManager.logEntity(this, a.name());
		attack = a;
		attackT = 0;
		marks.clear();
		hit.clear();
		switch (a) {
			case ROAR -> triggerAnim("main", "roar");
			case SLAM -> triggerAnim("main", "slam");
			case THROW -> triggerAnim("main", "throw");
			case PILLARS -> triggerAnim("main", "attack");
			default -> { }
		}
	}

	private void finish(int nextCooldown) {
		attack = Attack.NONE;
		cooldown = nextCooldown - phase * 12 + random.nextInt(20);
	}

	/** Phase change: it rears up and roars, the heart in its chest tears open – a red pulse and a shockwave. */
	private void tickRoar(ServerLevel sl) {
		if (attackT == 1) {
			sl.playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, net.minecraft.sounds.SoundSource.HOSTILE, 4f, 0.5f);
			sl.playSound(null, blockPosition(), SoundEvents.WARDEN_ROAR, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.6f);
		}
		if (attackT < 36) {
			sl.sendParticles(ParticleTypes.FLAME, getX(), getY() + getBbHeight() * 0.62, getZ(), 6, 0.3, 0.3, 0.3, 0.06);
			if (attackT % 4 == 0) {
				sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xFF2010, 2.5f), getX(), getY() + 2.2, getZ(), 10, 0.6, 0.8, 0.6, 0);
			}
		}
		if (attackT == 18) {
			screenHit(sl, phase >= 3 ? 1.6f : 1.2f, 0xFF8A0A0A, 0.45f);
			sl.playSound(null, blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.HEART_PULSE, net.minecraft.sounds.SoundSource.HOSTILE, 4f, 0.5f);
			sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 2.2, getZ(), 1, 0, 0, 0, 0);
			sl.sendParticles(ParticleTypes.LAVA, getX(), getY() + 2.2, getZ(), 40, 0.5, 0.5, 0.5, 0);
			waves.add(new Object[] {position(), 0.0, "ring", 12.0});
		}
		if (attackT >= 44) {
			finish(50);
		}
	}

	/** Fists up (they glow), then down: a shockwave rolls outward and cracks race towards everyone nearby. */
	private void tickSlam(ServerLevel sl) {
		if (attackT < 24) {
			for (int side : new int[] {-1, 1}) {
				var arm = position().add(getLookAngle().yRot((float) Math.PI / 2 * side).scale(1.1)).add(0, 3.6, 0);
				sl.sendParticles(ParticleTypes.FLAME, arm.x, arm.y, arm.z, 2, 0.15, 0.15, 0.15, 0.01);
			}
			if (attackT == 4) {
				sl.playSound(null, blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.RUMBLE, net.minecraft.sounds.SoundSource.HOSTILE, 2f, 1.3f);
			}
			return;
		}
		if (attackT == 24) {
			sl.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.5f);
			sl.playSound(null, blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.IMPACT, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.6f);
			screenHit(sl, 1.4f, 0, 0);
			waves.add(new Object[] {position(), 0.0, "ring", 10.0});
			for (var p : playersNear(sl, 18)) {
				waves.add(new Object[] {position(), 0.0, "crack", p.position()});
			}
		}
		if (attackT >= 34) {
			finish(70);
		}
	}

	/** Glowing rings appear under the actors; a moment later lava pillars burst out of them. */
	private void tickPillars(ServerLevel sl) {
		if (attackT == 1) {
			for (var p : playersNear(sl, 24)) {
				marks.add(p.position());
				double a = random.nextDouble() * Math.PI * 2;
				marks.add(p.position().add(Math.cos(a) * 3, 0, Math.sin(a) * 3));
			}
			if (marks.isEmpty()) {
				// nobody to aim at (trailer, empty set): the ground around the guardian breaks open
				for (int i = 0; i < 6; i++) {
					double a = i * Math.PI / 3 + random.nextDouble() * 0.5, d = 4 + random.nextDouble() * 4;
					marks.add(position().add(Math.cos(a) * d, 0, Math.sin(a) * d));
				}
			}
			sl.playSound(null, blockPosition(), SoundEvents.LAVA_AMBIENT, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.5f);
		}
		if (attackT < 28) {
			for (var m : marks) {
				for (int i = 0; i < 3; i++) {
					double a = random.nextDouble() * Math.PI * 2;
					sl.sendParticles(ParticleTypes.FLAME, m.x + Math.cos(a) * 1.2, m.y + 0.1, m.z + Math.sin(a) * 1.2, 1, 0, 0.01, 0, 0);
				}
				if (attackT % 6 == 0) {
					sl.sendParticles(ParticleTypes.LAVA, m.x, m.y + 0.2, m.z, 1, 0.3, 0, 0.3, 0);
				}
			}
			return;
		}
		if (attackT == 28) {
			screenHit(sl, 0.9f, 0xFFFF6A10, 0.25f);
			for (var m : marks) {
				sl.playSound(null, net.minecraft.core.BlockPos.containing(m), SoundEvents.FIRECHARGE_USE, net.minecraft.sounds.SoundSource.HOSTILE, 2f, 0.5f);
			}
		}
		if (attackT < 46) {
			for (var m : marks) {
				for (int i = 0; i < 8; i++) {
					sl.sendParticles(ParticleTypes.FLAME, true, true, m.x + (random.nextDouble() - 0.5) * 0.6, m.y + 0.2, m.z + (random.nextDouble() - 0.5) * 0.6,
							0, 0, 0.5 + random.nextDouble() * 0.4, 0, 1);
				}
				sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xFF7A18, 2.8f), true, true, m.x, m.y + random.nextDouble() * 6, m.z,
						3, 0.25, 0.4, 0.25, 0);
				sl.sendParticles(ParticleTypes.LARGE_SMOKE, m.x, m.y + 6, m.z, 1, 0.3, 0.3, 0.3, 0.02);
				if (attackT < 36) {
					for (var p : playersNear(sl, 30)) {
						if (!hit.contains(p.getUUID()) && p.position().distanceTo(m) < 1.4) {
							hit.add(p.getUUID());
							damage(sl, p, 8, m, 0.4, 0.9);
							p.igniteForSeconds(2);
						}
					}
				}
			}
			return;
		}
		finish(80);
	}

	/** Tears a rock out of the ground, lifts it over its head and hurls it where the actor is running to. */
	private void tickThrow(ServerLevel sl) {
		if (attackT == 6) {
			rock = new net.minecraft.world.entity.Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, sl);
			rock.setBlockState(net.minecraft.world.level.block.Blocks.BLACKSTONE.defaultBlockState());
			rock.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f(-1, -1, -1), null, new org.joml.Vector3f(2, 2, 2), null));
			rock.setPosRotInterpolationDuration(2);
			rock.addTag("btb_debris");
			rock.setPos(getX(), getY(), getZ());
			sl.addFreshEntity(rock);
			sl.playSound(null, blockPosition(), SoundEvents.STONE_BREAK, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.4f);
			sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
					net.minecraft.world.level.block.Blocks.BLACKSTONE.defaultBlockState()), getX(), getY() + 0.2, getZ(), 40, 1, 0.2, 1, 0.2);
		}
		if (rock != null && attackT > 6 && attackT <= 27) {
			double lift = Math.min(1, (attackT - 6) / 14.0);
			rock.setPos(getX(), getY() + 0.5 + lift * 4.2, getZ());
			sl.sendParticles(ParticleTypes.FLAME, rock.getX(), rock.getY(), rock.getZ(), 2, 0.6, 0.6, 0.6, 0.01);
		}
		if (attackT == 27 && rock != null) {
			var t = getTarget();
			net.minecraft.world.phys.Vec3 aim = t != null ? t.position().add(t.getDeltaMovement().multiply(10, 0, 10)) : position().add(getLookAngle().scale(8));
			rockFrom = rock.position();
			rockTo = aim;
			sl.playSound(null, blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.WHOOSH, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.6f);
		}
		if (attackT > 27 && rock != null && rockTo != null) {
			double k = Math.min(1, (attackT - 27) / 16.0);
			double arc = Math.sin(k * Math.PI) * 3.5;
			var pos = rockFrom.lerp(rockTo, k).add(0, arc, 0);
			rock.setPos(pos.x, pos.y, pos.z);
			sl.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 2, 0.3, 0.3, 0.3, 0.01);
			sl.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 3, 0.4, 0.4, 0.4, 0.02);
			if (k >= 1) {
				sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
				sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
						net.minecraft.world.level.block.Blocks.BLACKSTONE.defaultBlockState()), pos.x, pos.y + 0.5, pos.z, 80, 1.5, 0.5, 1.5, 0.3);
				sl.playSound(null, net.minecraft.core.BlockPos.containing(pos), SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.HOSTILE, 3f, 0.6f);
				screenHit(sl, 1.2f, 0, 0);
				for (var p : playersNear(sl, 40)) {
					if (p.position().distanceTo(pos) < 2.8) {
						damage(sl, p, 9, pos, 0.9, 0.6);
					}
				}
				rock.discard();
				rock = null;
				finish(90);
			}
		}
		if (attackT > 80) { // safety
			if (rock != null) {
				rock.discard();
				rock = null;
			}
			finish(60);
		}
	}

	/** Shockwave rings and ground cracks travel on their own, even while the next attack begins. */
	private void tickWaves(ServerLevel sl) {
		java.util.Iterator<Object[]> it = waves.iterator();
		while (it.hasNext()) {
			Object[] w = it.next();
			net.minecraft.world.phys.Vec3 c = (net.minecraft.world.phys.Vec3) w[0];
			double r = (double) w[1] + 0.75;
			w[1] = r;
			if (w[2].equals("ring")) {
				double max = (double) w[3];
				int n = (int) (r * 7);
				for (int i = 0; i < n; i++) {
					double a = i * Math.PI * 2 / n;
					double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
					var ground = sl.getBlockState(net.minecraft.core.BlockPos.containing(x, c.y - 0.5, z));
					sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
							ground.isAir() ? net.minecraft.world.level.block.Blocks.BLACKSTONE.defaultBlockState() : ground), x, c.y + 0.15, z, 1, 0.1, 0.05, 0.1, 0.1);
					if (i % 3 == 0) {
						sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, c.y + 0.3, z, 0, Math.cos(a) * 0.05, 0.02, Math.sin(a) * 0.05, 1);
					}
				}
				for (var p : playersNear(sl, max + 2)) {
					double d = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
					if (p.onGround() && Math.abs(d - r) < 0.9 && !hit.contains(p.getUUID())) {
						hit.add(p.getUUID());
						damage(sl, p, 7, c, 1.1, 0.55);
					}
				}
				if (r >= max) {
					it.remove();
				}
			} else {
				net.minecraft.world.phys.Vec3 to = (net.minecraft.world.phys.Vec3) w[3];
				double len = to.distanceTo(c);
				double k = Math.min(1, r / Math.max(1, len));
				var at = c.lerp(to, k);
				sl.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.1, at.z, 1, 0.1, 0, 0.1, 0);
				sl.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.1, at.z, 4, 0.25, 0.02, 0.25, 0.01);
				if (k >= 1) {
					sl.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 2, 0.4, 0.4, 0.4, 0);
					sl.sendParticles(ParticleTypes.FLAME, true, true, at.x, at.y + 0.2, at.z, 30, 0.6, 1.2, 0.6, 0.05);
					sl.playSound(null, net.minecraft.core.BlockPos.containing(at), SoundEvents.FIRECHARGE_USE, net.minecraft.sounds.SoundSource.HOSTILE, 2f, 0.7f);
					for (var p : playersNear(sl, 40)) {
						if (p.position().distanceTo(at) < 1.6) {
							damage(sl, p, 6, at, 0.5, 0.8);
						}
					}
					it.remove();
				}
			}
		}
	}

	@Override
	public boolean isInvulnerable() {
		return emergeTicks > 0 || attack == Attack.ROAR || super.isInvulnerable();
	}

	@Override
	public void remove(RemovalReason reason) {
		if (rock != null) {
			rock.discard();
		}
		super.remove(reason);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (level() instanceof ServerLevel fightLevel) {
			tickFight(fightLevel);
		}
		if (level() instanceof ServerLevel sl && tickCount % 6 == 0) {
			// embers from the heart in its chest
			sl.sendParticles(ParticleTypes.FLAME, getX(), getY() + getBbHeight() * 0.62, getZ(), 1, 0.2, 0.2, 0.2, 0.01);
		}
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		super.dropCustomDeathLoot(level, source, killedByPlayer);
		dropKeyDramatic(level, new net.minecraft.world.phys.Vec3(getX(), getY() + getBbHeight() * 0.62, getZ()), getYRot());
		level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 1.5, getZ(), 60, 0.6, 1.2, 0.6, 0.1);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
	}

	/** One key in flight: {item, ticks, landedAt (-1 = still flying)}. */
	private static final java.util.List<Object[]> KEYS = new java.util.ArrayList<>();
	private static final int LIE_TICKS = 60; // lies on the ground, glowing, before anyone can take it

	/**
	 * The key bursts out of the heart, flies in an arc with a trail of fire and light, slams into the
	 * ground (ring of embers, sparks, a short tremor), then lies there glowing for a moment.
	 */
	public static void dropKeyDramatic(ServerLevel level, net.minecraft.world.phys.Vec3 from, float facingYaw) {
		var key = new net.minecraft.world.entity.item.ItemEntity(level, from.x, from.y, from.z, new ItemStack(BtbItems.NETHER_KEY));
		key.setGlowingTag(true);
		key.setUnlimitedLifetime();
		key.setPickUpDelay(32767); // released after it has landed and lain for a moment
		// forward (where the guardian looked) and high up
		double yaw = Math.toRadians(facingYaw);
		key.setDeltaMovement(-Math.sin(yaw) * 0.16, 0.62, Math.cos(yaw) * 0.16);
		level.addFreshEntity(key);
		KEYS.add(new Object[] {key, new int[1], new int[] {-1}});
		level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD080), from.x, from.y, from.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.LAVA, from.x, from.y, from.z, 14, 0.3, 0.3, 0.3, 0);
		level.playSound(null, key.blockPosition(), SoundEvents.TRIDENT_RIPTIDE_3.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 1.4f, 0.7f);
		level.playSound(null, key.blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.CRYSTAL_SHIMMER,
				net.minecraft.sounds.SoundSource.NEUTRAL, 1.2f, 0.8f);
	}

	/** Called every server tick (registered in BtbEntities): flight trail, impact, lying, release. */
	public static void tickFloatingKeys() {
		java.util.Iterator<Object[]> it = KEYS.iterator();
		while (it.hasNext()) {
			Object[] f = it.next();
			var key = (net.minecraft.world.entity.item.ItemEntity) f[0];
			if (key.isRemoved() || !(key.level() instanceof ServerLevel sl)) {
				it.remove();
				continue;
			}
			int t = ++((int[]) f[1])[0];
			int[] landed = (int[]) f[2];
			double x = key.getX(), y = key.getY() + 0.2, z = key.getZ();
			if (landed[0] < 0) {
				// in flight: trail of fire and light
				sl.sendParticles(ParticleTypes.FLAME, x, y, z, 2, 0.04, 0.04, 0.04, 0.005);
				sl.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0.02, 0.02, 0.02, 0);
				sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xFFB030, 1.4f), x, y, z, 2, 0.05, 0.05, 0.05, 0);
				if (t % 6 == 0) {
					sl.playSound(null, key.blockPosition(), SoundEvents.BLAZE_SHOOT, net.minecraft.sounds.SoundSource.NEUTRAL, 0.25f, 1.8f);
				}
				if ((key.onGround() && t > 4) || t > 200) {
					landed[0] = t;
					key.setDeltaMovement(0, 0, 0);
					// impact: ring of embers, sparks, dust, a short tremor
					for (int i = 0; i < 36; i++) {
						double a = i * Math.PI * 2 / 36;
						sl.sendParticles(ParticleTypes.FLAME, x + Math.cos(a) * 0.4, key.getY() + 0.05, z + Math.sin(a) * 0.4, 0,
								Math.cos(a), 0.02, Math.sin(a), 0.18);
						sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * 0.3, key.getY() + 0.05, z + Math.sin(a) * 0.3, 0,
								Math.cos(a), 0.05, Math.sin(a), 0.1);
					}
					sl.sendParticles(ParticleTypes.LAVA, x, key.getY() + 0.1, z, 20, 0.3, 0.1, 0.3, 0);
					sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, key.getY() + 0.1, z, 10, 0.5, 0.05, 0.5, 0.02);
					sl.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD080), x, y, z, 1, 0, 0, 0, 0);
					sl.playSound(null, key.blockPosition(), SoundEvents.ANVIL_LAND, net.minecraft.sounds.SoundSource.NEUTRAL, 0.9f, 0.55f);
					sl.playSound(null, key.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.6f, 1.6f);
					var server = sl.getServer();
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput().withLevel(sl)
							.withPosition(key.position()), "btb effects shake 0.45 0.7 @a[distance=..30]");
				}
				continue;
			}
			int lying = t - landed[0];
			// lies there glowing: a slim column of light and a soft pulse
			if (t % 4 == 0) {
				for (int h = 0; h < 6; h++) {
					sl.sendParticles(ParticleTypes.END_ROD, x, key.getY() + 0.3 + h * 0.5, z, 1, 0.02, 0.05, 0.02, 0);
				}
			}
			if (t % 5 == 0) {
				sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xFFC040, 0.9f), x, y, z, 2, 0.25, 0.1, 0.25, 0);
			}
			if (t % 20 == 0) {
				sl.sendParticles(ParticleTypes.FLAME, x, key.getY() + 0.05, z, 6, 0.35, 0, 0.35, 0.01);
			}
			if (lying == LIE_TICKS) {
				key.setPickUpDelay(0); // now it can be taken
				sl.playSound(null, key.blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.CRYSTAL_SHIMMER,
						net.minecraft.sounds.SoundSource.NEUTRAL, 0.8f, 1.4f);
			}
			if (lying % 40 == 0) {
				sl.playSound(null, key.blockPosition(), de.behindtheblocks.cinematic.sound.BtbSounds.CRYSTAL_SHIMMER,
						net.minecraft.sounds.SoundSource.NEUTRAL, 0.4f, 1.3f);
			}
		}
	}

	@Override
	public boolean fireImmune() {
		return true;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.RAVAGER_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.IRON_GOLEM_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.WARDEN_DEATH;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<HeartWarden>("main", 4,
				test -> {
					boolean walking = !isEmerging() && test.isMoving();
					// stride speed follows the real walking speed: no sliding feet
					test.setControllerSpeed(walking ? Math.max(0.7f, Math.min(2f, 0.5f + test.animatable().walkAnimation.speed() * 2.2f)) : 1f);
					return test.setAndContinue(isEmerging() ? EMERGE : walking ? WALK : IDLE);
				})
				.triggerableAnim("attack", ATTACK).triggerableAnim("slam", SLAM).triggerableAnim("throw", THROW)
				.triggerableAnim("roar", ROAR));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
