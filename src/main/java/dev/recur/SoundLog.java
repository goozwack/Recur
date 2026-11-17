package dev.recur;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

/**
 * Clones make the same sounds and effects their player did. While a player is recorded, these are saved with their
 * world changes (kind 9 for sounds, kind 10 for level events such as lava fizzing or a block breaking):
 * <ul>
 * <li>sounds sent to everyone except them, which their own game had already played (footsteps, splashing, eating,
 * placing blocks, putting out fire...);</li>
 * <li>sounds and effects that happen during one of their actions, or that come from them.</li>
 * </ul>
 * When the clone plays, the recorded ones are played at the same moment. Anything a clone's replay would set off by
 * itself is dropped, so nothing is heard twice and a clone never makes a sound its player didn't.
 */
public final class SoundLog {
	public static final int KIND_SOUND = 9;
	public static final int KIND_LEVEL_EVENT = 10;
	/** Particles a mob sent (a warden's sonic boom, vibrations travelling to it...). */
	public static final int KIND_PARTICLES = 14;

	/**
	 * Most sounds a mob makes have no source given: the mob being ticked, or hurt, right then made them. A mob's idle
	 * noises are told apart, since a mob being played back still makes those by itself.
	 */
	public static Entity ticking;
	private static final java.util.ArrayDeque<Entity> HURTING = new java.util.ArrayDeque<>();
	public static int ambient;

	public static void startHurt(Entity e) { HURTING.push(e); }

	/** What players are hitting right now (Player.attack). */
	private static final java.util.ArrayDeque<Entity> ATTACKING = new java.util.ArrayDeque<>();

	public static void startAttack(Entity target) { ATTACKING.push(target); }

	public static void endAttack() { if (!ATTACKING.isEmpty()) ATTACKING.pop(); }

	/** The mob a player is hitting right now, or null. */
	static net.minecraft.world.entity.Mob attackTarget() {
		return !ATTACKING.isEmpty() && ATTACKING.peek() instanceof net.minecraft.world.entity.Mob m ? m : null;
	}

	/**
	 * The sound of a clone's hit, and its crit, are skipped when the mob it hit isn't there for it in this run: a player
	 * killed it, it left its replay, or it isn't the one the clone plays back.
	 */
	static boolean targetGone(ServerLevel level, CompoundTag d) {
		if (!d.contains("tgt")) return false;
		java.util.UUID id;
		try { id = java.util.UUID.fromString(d.getStringOr("tgt", "")); } catch (IllegalArgumentException e) { return false; }
		Entity target = com.mt1006.mocap.mocap.playing.Controlled.alias(id);
		if (target == null) target = level.getEntity(id);
		if (target == null || com.mt1006.mocap.mocap.playing.Controlled.hasLeft(target)) return true;
		return !com.mt1006.mocap.mocap.playing.Controlled.isControlled(target) && !BossReplay.isBoss(target);
	}

	public static void endHurt() { if (!HURTING.isEmpty()) HURTING.pop(); }

	/** The mob a sound or effect comes from, or null. */
	private static net.minecraft.world.entity.Mob mobSource(Entity except, Entity sourceEntity) {
		if (sourceEntity instanceof net.minecraft.world.entity.Mob m) return m;
		if (except instanceof net.minecraft.world.entity.Mob m) return m;
		if (sourceEntity != null || except instanceof net.minecraft.world.entity.player.Player) return null;
		Entity e = !HURTING.isEmpty() ? HURTING.peek() : ticking;
		return e instanceof net.minecraft.world.entity.Mob m ? m : null;
	}

	/**
	 * A mob a clone plays back doesn't make the sounds its AI or its steps would: the recorded ones play instead. It
	 * still makes its idle noises (those aren't recorded), and what a live player does to it is heard as usual.
	 */
	private static boolean replayedMobSound(net.minecraft.world.entity.Mob mob) {
		return mob != null && ambient == 0 && WorldLog.actor() == null && !BossReplay.isBoss(mob)
			&& com.mt1006.mocap.mocap.playing.Controlled.isControlled(mob);
	}

	/** The recording player a mob is tied to, or null. */
	private static ServerPlayer tiedTo(net.minecraft.world.entity.Mob mob) {
		java.util.UUID owner = EntityOwners.ownerOf(mob);
		if (owner == null || mob.level().getServer() == null) return null;
		ServerPlayer p = mob.level().getServer().getPlayerList().getPlayer(owner);
		return p != null && WorldLog.isRecording(p) ? p : null;
	}

	/** Saves an effect a mob made: in its player's recording, or with its history if it isn't tied to anyone yet. */
	private static void saveMobEffect(net.minecraft.world.entity.Mob mob, int kind, ServerLevel level, BlockPos pos, CompoundTag data) {
		data.putString("src", mob.getUUID().toString());
		ServerPlayer player = tiedTo(mob);
		if (player != null) WorldLog.addEffect(player, kind, level, pos, data);
		else MobHistory.note(mob, WorldLog.effectEdit(kind, level, pos, data));
	}

	/** Particles were sent: a mob's are recorded (see KIND_PARTICLES). */
	public static void onParticles(ServerLevel level, net.minecraft.core.particles.ParticleOptions options, boolean overrideLimiter,
								   boolean alwaysShow, double x, double y, double z, int count, double dx, double dy, double dz, double speed,
								   double a, double b, net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket.RandomizationType randomization) {
		if (playingRecorded || !LoopOverlay.active || fromReplay(null) || ambient > 0) return;
		// a player's sweeping attack: everyone sees it in the air, so their clone's does too
		ServerPlayer sweeper = sweeping;
		boolean sweep = sweeper != null && options.getType() == net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK;
		net.minecraft.world.entity.Mob mob = sweep ? null : mobSource(null, null);
		if (!sweep && (mob == null || com.mt1006.mocap.mocap.playing.Controlled.isControlled(mob))) return;
		var encoded = net.minecraft.core.particles.ParticleTypes.CODEC
			.encodeStart(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), options).result();
		if (encoded.isEmpty()) return;
		CompoundTag data = new CompoundTag();
		data.put("o", encoded.get());
		data.putBoolean("ol", overrideLimiter); data.putBoolean("as", alwaysShow);
		data.putDouble("x", x); data.putDouble("y", y); data.putDouble("z", z);
		data.putInt("n", count);
		data.putDouble("dx", dx); data.putDouble("dy", dy); data.putDouble("dz", dz);
		data.putDouble("sp", speed); data.putDouble("a", a); data.putDouble("b", b);
		data.putInt("rt", randomization.ordinal());
		if (sweep) WorldLog.addEffect(sweeper, KIND_PARTICLES, level, BlockPos.containing(x, y, z), data);
		else saveMobEffect(mob, KIND_PARTICLES, level, BlockPos.containing(x, y, z), data);
	}

	/** The recording player whose sweeping attack is happening right now (see PlayerAttackMixin), or null. */
	private static ServerPlayer sweeping;

	public static void startSweep(net.minecraft.world.entity.player.Player player) {
		sweeping = player instanceof ServerPlayer sp && !(sp instanceof com.mt1006.mocap.utils.FakePlayer)
			&& ReplayScope.depth == 0 && WorldLog.isRecording(sp) ? sp : null;
	}

	public static void endSweep() { sweeping = null; }

	/** Recorded effects of a mob that isn't being played back any more (it's fighting a player for real) are skipped. */
	private static boolean sourceGone(ServerLevel level, CompoundTag d) {
		if (!d.contains("src")) return false;
		java.util.UUID id;
		try { id = java.util.UUID.fromString(d.getStringOr("src", "")); } catch (IllegalArgumentException e) { return false; }
		Entity src = com.mt1006.mocap.mocap.playing.Controlled.alias(id);
		if (src == null) src = level.getEntity(id);
		if (src == null) return false; // gone (it died): what it did still plays
		return com.mt1006.mocap.mocap.playing.Controlled.hasLeft(src)
			|| (!com.mt1006.mocap.mocap.playing.Controlled.isControlled(src) && !BossReplay.isBoss(src));
	}
	/** Recorded effects this many ticks late are skipped (a run starting at a checkpoint catches up on older ones). */
	private static final int TOO_LATE = 10;

	/** True while a recorded effect is being played, which must go through. */
	private static boolean playingRecorded;

	private SoundLog() {}

	/** Returns false to drop the sound. */
	public static boolean onSound(ServerLevel level, Entity except, Entity sourceEntity, double x, double y, double z, Holder<SoundEvent> sound,
								  SoundSource source, float volume, float pitch, long seed) {
		if (playingRecorded) return true;
		if (fromReplay(except)) return false;
		net.minecraft.world.entity.Mob mob = LoopOverlay.active ? mobSource(except, sourceEntity) : null;
		if (replayedMobSound(mob)) return false;
		ServerPlayer player = owner(except, sourceEntity);
		if (player == null && (mob == null || ambient > 0)) return true;
		CompoundTag data = new CompoundTag();
		data.putString("s", soundId(sound).toString());
		data.putString("c", source.name());
		data.putDouble("x", x); data.putDouble("y", y); data.putDouble("z", z);
		data.putFloat("v", volume); data.putFloat("p", pitch);
		data.putLong("r", seed);
		if (player != null) {
			if (mob != null) data.putString("src", mob.getUUID().toString());
			WorldLog.addEffect(player, KIND_SOUND, level, BlockPos.containing(x, y, z), data);
		} else {
			saveMobEffect(mob, KIND_SOUND, level, BlockPos.containing(x, y, z), data);
		}
		return true;
	}

	/** Returns false to drop the event. */
	public static boolean onLevelEvent(ServerLevel level, Entity source, int type, BlockPos pos, int data) {
		if (playingRecorded) return true;
		if (fromReplay(source)) return false;
		net.minecraft.world.entity.Mob mob = LoopOverlay.active ? mobSource(source, null) : null;
		if (replayedMobSound(mob)) return false;
		ServerPlayer player = owner(source, null);
		if (player == null && (mob == null || ambient > 0)) return true;
		CompoundTag tag = new CompoundTag();
		tag.putInt("t", type);
		tag.putInt("a", data);
		if (player != null) {
			if (mob != null) tag.putString("src", mob.getUUID().toString());
			WorldLog.addEffect(player, KIND_LEVEL_EVENT, level, pos.immutable(), tag);
		} else {
			saveMobEffect(mob, KIND_LEVEL_EVENT, level, pos.immutable(), tag);
		}
		return true;
	}

	/** Set off by a clone's replay: by the clone itself, or while its actions or world changes are being replayed. */
	private static boolean fromReplay(Entity exceptOrSource) {
		return exceptOrSource instanceof FakePlayer || (ReplayScope.depth > 0 && LoopOverlay.active);
	}

	/** The recording player this effect belongs to, or null. */
	private static ServerPlayer owner(Entity exceptOrSource, Entity sourceEntity) {
		if (exceptOrSource instanceof ServerPlayer p && WorldLog.isRecording(p)) return p;
		if (sourceEntity instanceof ServerPlayer p && WorldLog.isRecording(p)) return p;
		ServerPlayer actor = WorldLog.actor();
		return actor != null && WorldLog.isRecording(actor) ? actor : null;
	}

	private static Identifier soundId(Holder<SoundEvent> sound) {
		return sound.unwrapKey().map(net.minecraft.resources.ResourceKey::identifier).orElseGet(() -> sound.value().location());
	}

	/** Plays a recorded effect for everyone nearby. Returns true once it's done with (played or skipped). */
	static boolean play(ServerLevel level, WorldLog.Edit e, long now) {
		if (e.tick() + TOO_LATE < now) return true;
		CompoundTag d = e.data();
		if (sourceGone(level, d) || targetGone(level, d)) return true;
		if (e.kind() == KIND_PARTICLES) {
			var options = net.minecraft.core.particles.ParticleTypes.CODEC
				.parse(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), d.get("o")).result();
			if (options.isEmpty()) return true;
			var types = net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket.RandomizationType.values();
			var randomization = types[Math.max(0, Math.min(types.length - 1, d.getIntOr("rt", 0)))];
			allow(() -> level.sendParticles(options.get(), d.getBooleanOr("ol", false), d.getBooleanOr("as", false),
				d.getDoubleOr("x", 0), d.getDoubleOr("y", 0), d.getDoubleOr("z", 0), d.getIntOr("n", 1),
				d.getDoubleOr("dx", 0), d.getDoubleOr("dy", 0), d.getDoubleOr("dz", 0), d.getDoubleOr("sp", 0),
				d.getDoubleOr("a", 0), d.getDoubleOr("b", 0), randomization));
			return true;
		}
		if (e.kind() == KIND_LEVEL_EVENT) {
			int type = d.getIntOr("t", 0), data = d.getIntOr("a", 0);
			BlockPos pos = e.pos();
			LoopNetworking.CloneEffectPayload payload = new LoopNetworking.CloneEffectPayload(true, "", 0, pos.getX(), pos.getY(), pos.getZ(),
				0, 0, 0, type, data);
			var packet = new ClientboundLevelEventPacket(type, pos, data, false);
			sendNearby(level, pos.getX(), pos.getY(), pos.getZ(), LEVEL_EVENT_RANGE, payload, packet);
			return true;
		}
		Identifier id = Identifier.tryParse(d.getStringOr("s", ""));
		if (id == null) return true;
		Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.get(id).map(h -> (Holder<SoundEvent>) h)
			.orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
		SoundSource source;
		try { source = SoundSource.valueOf(d.getStringOr("c", "PLAYERS")); }
		catch (IllegalArgumentException ex) { source = SoundSource.PLAYERS; }
		double x = d.getDoubleOr("x", 0), y = d.getDoubleOr("y", 0), z = d.getDoubleOr("z", 0);
		float volume = d.getFloatOr("v", 1f), pitch = d.getFloatOr("p", 1f);
		long seed = d.getLongOr("r", 0);
		cloneSound(level, x, y, z, sound, source, volume, pitch, seed);
		return true;
	}

	/** Plays a sound a clone makes, for everyone nearby, at each player's clone action volume. */
	public static void cloneSound(ServerLevel level, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume,
								  float pitch, long seed) {
		Identifier id = soundId(sound);
		var payload = new LoopNetworking.CloneEffectPayload(false, id.toString(), source.ordinal(), x, y, z, volume, pitch, seed, 0, 0);
		var packet = new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed);
		sendNearby(level, x, y, z, sound.value().getRange(volume), payload, packet);
	}

	/** How far away level events (block breaking, lava fizzing...) are heard, as in ServerLevel.levelEvent. */
	private static final double LEVEL_EVENT_RANGE = 64.0;

	/** Everyone in range gets Recur's packet if their game has Recur, or the normal one otherwise. */
	private static void sendNearby(ServerLevel level, double x, double y, double z, double range, LoopNetworking.CloneEffectPayload payload,
								   net.minecraft.network.protocol.Packet<?> vanilla) {
		for (ServerPlayer p : level.players()) {
			if (p instanceof FakePlayer || p.distanceToSqr(x, y, z) > range * range) continue;
			if (ServerPlayNetworking.canSend(p, LoopNetworking.CloneEffectPayload.TYPE)) ServerPlayNetworking.send(p, payload);
			else p.connection.send(vanilla);
		}
	}

	/** Runs something whose sounds should be heard even though a clone's replay set it off. */
	public static void allow(Runnable r) {
		boolean was = playingRecorded;
		playingRecorded = true;
		try { r.run(); }
		finally { playingRecorded = was; }
	}
}
