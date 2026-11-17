package dev.recur;

import dev.recur.mixin.CompoundContainerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records world changes a player makes that Mocap's replay does not reproduce on its own: container contents (chests,
 * barrels, furnaces, shulker boxes, chest boats, minecart chests...), sign text, item frames, armor stands, lecterns,
 * jukeboxes, pots, bookshelves, and right-click block state changes (levers, doors, composters...). Each change stores
 * the final state of the block/entity, and when that run's clone plays back, the same state is applied at the same
 * moment of the timeline.
 */
public final class WorldLog {
	/** kind 0 = block (state + optional block entity), 1 = entity (full data), 2 = entity removed. */
	public record Edit(long tick, String dim, int kind, BlockPos pos, UUID entity, CompoundTag data) {
		CompoundTag toTag() {
			CompoundTag t = new CompoundTag();
			t.putLong("t", tick); t.putString("d", dim); t.putInt("k", kind);
			t.putInt("x", pos.getX()); t.putInt("y", pos.getY()); t.putInt("z", pos.getZ());
			if (entity != null) t.putString("e", entity.toString());
			t.put("v", data);
			return t;
		}

		static Edit fromTag(CompoundTag t) {
			String e = t.getStringOr("e", "");
			return new Edit(t.getLongOr("t", 0), t.getStringOr("d", "minecraft:overworld"), t.getIntOr("k", 0),
				new BlockPos(t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0)),
				e.isEmpty() ? null : UUID.fromString(e), t.getCompoundOrEmpty("v"));
		}
	}

	/** Replays apply an edit slightly after its recorded tick so Mocap's own replay of the same click has happened. */
	private static final int APPLY_DELAY = 2;

	private record PendingBlock(UUID player, ServerLevel level, BlockPos pos, UUID src) {
		/** src: the mob whose explosion changed it (see beginMobExplosion), or null. */
		PendingBlock(UUID player, ServerLevel level, BlockPos pos) { this(player, level, pos, explosionSource); }
	}
	private record PendingEntity(UUID player, Entity entity) {}

	private static final Map<UUID, List<Edit>> CURRENT = new HashMap<>();
	private static final Set<PendingBlock> PENDING_BLOCKS = new LinkedHashSet<>();
	/** What a block a player broke was (so a clone doesn't break something else that's there by then). */
	private static final Map<PendingBlock, BlockState> BROKEN_WAS = new HashMap<>();
	private static final Set<PendingEntity> PENDING_ENTITIES = new LinkedHashSet<>();
	/** Damage players did to bosses this tick (see BossReplay). */
	private static final Map<BossHit, Float> BOSS_DAMAGE = new LinkedHashMap<>();
	/** ranged: an arrow, trident or wind charge, which an armoured wither shrugs off. */
	private record BossHit(UUID player, Entity entity, boolean ranged) {}
	/** Edits of playing clones, waiting for their moment (or for their chunk/entity to be loaded). */
	private static java.util.ArrayDeque<Edit> SCHEDULED = new java.util.ArrayDeque<>();
	/** Due edits whose chunk/entity wasn't loaded yet: retried once a second. */
	private static final List<Edit> RETRY = new ArrayList<>();
	private static final Map<String, ResourceKey<Level>> DIMS = new HashMap<>();

	/** Timeline tick of the latest flush (random ticks happen in between flushes). */
	private static long lastNow;

	/** The timeline tick that what happens right now is recorded at. */
	public static long now() { return lastNow + 1; }

	private WorldLog() {}

	public static void clearAll() {
		CURRENT.clear(); PENDING_BLOCKS.clear(); PENDING_ENTITIES.clear(); BOSS_DAMAGE.clear(); JOB_SITES.clear(); SCHEDULED.clear(); RETRY.clear();
		ACTOR_CHANGES.clear(); actor = null; actorDepth = 0;
		ContainerDeltas.clear();
		RandomTicks.clearAll();
	}

	public static void startRecording(UUID player) { CURRENT.put(player, new ArrayList<>()); }

	/** Is this player's run being recorded right now? */
	public static boolean isRecording(ServerPlayer player) { return !(player instanceof com.mt1006.mocap.utils.FakePlayer) && CURRENT.containsKey(player.getUUID()); }

	/** The recording player whose action the server is handling right now, or null. */
	public static ServerPlayer actor() { return actor; }

	/** Job site blocks (lecterns, composters...) a recording player placed or broke this run, and who did it. */
	private static final Map<net.minecraft.core.GlobalPos, UUID> JOB_SITES = new HashMap<>();

	private static boolean isJobSite(BlockState state) {
		return net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(state)
			.map(t -> t.is(net.minecraft.tags.PoiTypeTags.ACQUIRABLE_JOB_SITE)).orElse(false);
	}

	/** A block is changing during this player's action: if it is (or was) a job site, remember who changed it. */
	private static void noteJobSite(Level level, BlockPos pos, BlockState state) {
		if (actor == null || ReplayScope.depth > 0 || !(level instanceof ServerLevel) || !isJobSite(state)) return;
		JOB_SITES.put(net.minecraft.core.GlobalPos.of(level.dimension(), pos.immutable()), actor.getUUID());
	}

	/**
	 * A villager took a job (or lost one) at a job site a recording player placed or broke: the villager is recorded as
	 * it is now, like a mob they hit, so it has that job again in later runs.
	 */
	public static void onVillagerJobChanged(net.minecraft.world.entity.npc.villager.Villager villager) {
		if (JOB_SITES.isEmpty() || ReplayScope.depth > 0 || !(villager.level() instanceof ServerLevel)) return;
		var brain = villager.getBrain();
		UUID who = null;
		for (var memory : List.of(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
			net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE)) {
			var site = brain.getMemory(memory);
			if (site.isPresent() && JOB_SITES.containsKey(site.get())) { who = JOB_SITES.get(site.get()); break; }
		}
		if (who == null) {
			// It lost its job: the job site it had is gone, so look for one broken right next to it.
			for (var entry : JOB_SITES.entrySet()) {
				if (entry.getKey().dimension() == villager.level().dimension() && entry.getKey().pos().closerToCenterThan(villager.position(), 48)) {
					who = entry.getValue();
					break;
				}
			}
		}
		if (who == null) return;
		ServerPlayer player = villager.level().getServer().getPlayerList().getPlayer(who);
		if (player != null && recording(player)) {
			PENDING_ENTITIES.add(new PendingEntity(who, villager));
			MobTies.direct(player, villager); // and its whole life is replayed, like any mob you did something with
		}
	}

	/** Kind of the edit noting that this player started (or made bigger) a raid. */
	public static final int KIND_RAID = 12;

	/** Kind of the edit noting that a mob tied to this player blew up (a creeper). */
	public static final int KIND_EXPLOSION = 15;
	/** The mob blowing up right now, whose broken blocks are tagged with it. */
	private static UUID explosionSource;
	private static Entity exploding;

	/**
	 * A mob tied to a recording player is blowing up: the explosion is theirs, like TNT they lit. The blocks it breaks
	 * are recorded exactly (so every replay breaks the same ones), and the blast itself is too.
	 */
	public static void beginMobExplosion(Entity source, net.minecraft.world.phys.Vec3 center, float radius) {
		if (!(source instanceof net.minecraft.world.entity.Mob mob) || actor != null || ReplayScope.depth > 0) return;
		boolean wither = mob instanceof net.minecraft.world.entity.boss.wither.WitherBoss;
		UUID owner = EntityOwners.ownerOf(mob);
		if (mob.level().getServer() == null) return;
		ServerPlayer player = owner != null ? mob.level().getServer().getPlayerList().getPlayer(owner) : null;
		// A wither blowing up after powering up usually belongs to nobody: the nearest player recording gets its crater,
		// so it's the same crater every run from then on (see BossReplay.powerUp).
		if (wither && (player == null || !recording(player))) player = nearestRecording((ServerLevel) mob.level(), center, 96);
		if (player == null || !recording(player)) return;
		CompoundTag data = new CompoundTag();
		if (wither) data.putBoolean("wither", true);
		data.putDouble("x", center.x); data.putDouble("y", center.y); data.putDouble("z", center.z);
		data.putFloat("r", radius);
		data.putString("src", mob.getUUID().toString());
		addEffect(player, KIND_EXPLOSION, (ServerLevel) mob.level(), BlockPos.containing(center), data);
		beginAction(player);
		if (actor == player) { exploding = mob; explosionSource = mob.getUUID(); }
	}

	private static ServerPlayer nearestRecording(ServerLevel level, net.minecraft.world.phys.Vec3 at, double range) {
		ServerPlayer best = null;
		double bestDist = range * range;
		for (ServerPlayer p : level.players()) {
			if (p instanceof com.mt1006.mocap.utils.FakePlayer || !recording(p)) continue;
			double d = p.distanceToSqr(at);
			if (d < bestDist) { bestDist = d; best = p; }
		}
		return best;
	}

	/** Is a recorded wither crater due about now for this wither? Then that's its blast (see BossReplay.powerUp). */
	public static boolean craterComing(ServerLevel level, Entity wither) {
		for (Edit e : SCHEDULED) if (isCrater(level, e, wither) && e.tick < applyNow + 200) return true;
		for (Edit e : RETRY) if (isCrater(level, e, wither)) return true;
		return false;
	}

	private static boolean isCrater(ServerLevel level, Edit e, Entity wither) {
		if (e.kind != KIND_EXPLOSION || !e.data.getBooleanOr("wither", false) || e.tick + 200 < applyNow) return false;
		Entity src = sourceOf(level, e.data);
		return src == wither || (src == null && wither.distanceToSqr(e.data.getDoubleOr("x", 0), e.data.getDoubleOr("y", 0), e.data.getDoubleOr("z", 0)) < 32 * 32);
	}

	public static void endMobExplosion(Entity source) {
		if (source == null || source != exploding) return;
		ServerPlayer player = actor;
		exploding = null;
		explosionSource = null;
		if (player != null) endAction(player);
	}

	/**
	 * The blast again: its flash, sound, push and damage, but no blocks (the recorded ones break). Skipped when the mob
	 * left its replay to fight a player for real, since then it blows up by itself, if it does.
	 */
	private static boolean replayExplosion(ServerLevel level, Edit e) {
		Entity src = sourceOf(level, e.data);
		if (e.data.getBooleanOr("wither", false)) {
			// The wither's recorded blast: its crater (the broken blocks) comes with it. Once, even if lag made it late.
			if (e.tick + 200 < applyNow) return true;
			if (src == null) src = BossReplay.nearestOfType(level, "minecraft:wither", e.pos);
			if (src instanceof net.minecraft.world.entity.boss.wither.WitherBoss w && !BossReplay.blast(w)) return true;
		} else {
			// A wither's blast from before its craters were recorded: the wither blows up by itself (see BossReplay.powerUp).
			if (src instanceof net.minecraft.world.entity.boss.wither.WitherBoss) return true;
			if (e.tick + 5 < applyNow) return true;
		}
		if (src != null && com.mt1006.mocap.mocap.playing.Controlled.hasLeft(src)) return true;
		level.explode(null, null, null, e.data.getDoubleOr("x", 0), e.data.getDoubleOr("y", 0), e.data.getDoubleOr("z", 0),
			e.data.getFloatOr("r", 3f), false, Level.ExplosionInteraction.NONE,
			net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
			net.minecraft.util.random.WeightedList.of(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
		return true;
	}

	/** The mob an edit names as its cause ("src"), as it is in this run, or null. */
	private static Entity sourceOf(ServerLevel level, CompoundTag data) {
		if (!data.contains("src")) return null;
		UUID id;
		try { id = UUID.fromString(data.getStringOr("src", "")); } catch (IllegalArgumentException ex) { return null; }
		Entity src = com.mt1006.mocap.mocap.playing.Controlled.alias(id);
		return src != null ? src : level.getEntity(id);
	}

	/** An effect (sound, particles...) as an edit of this tick, for a mob's history (see MobHistory). */
	public static Edit effectEdit(int kind, ServerLevel level, BlockPos pos, CompoundTag data) {
		return new Edit(lastNow + 1, level.dimension().identifier().toString(), kind, pos, null, data);
	}

	/** A recording took a mob over: what it made earlier in the run (its sounds, particles) goes in with it. */
	public static void adopt(UUID player, List<Edit> edits) {
		List<Edit> list = CURRENT.get(player);
		if (list == null || edits.isEmpty()) return;
		list.addAll(edits);
		list.sort(java.util.Comparator.comparingLong(Edit::tick));
	}
	/** Kind of the edit noting a critical hit, so the clone's hit shows the same particles. */
	public static final int KIND_CRIT = 13;

	public static void onCrit(ServerPlayer player, Entity target, boolean magic) {
		if (player instanceof com.mt1006.mocap.utils.FakePlayer || ReplayScope.depth > 0 || !isRecording(player)) return;
		CompoundTag data = new CompoundTag();
		data.putString("target", target.getUUID().toString());
		data.putDouble("x", target.getX());
		data.putDouble("y", target.getY());
		data.putDouble("z", target.getZ());
		data.putBoolean("magic", magic);
		addEffect(player, KIND_CRIT, player.level(), target.blockPosition(), data);
	}

	/** The crit particles on what was hit: the same entity, or whatever is standing there now. */
	private static boolean replayCrit(ServerLevel level, Edit e) {
		if (e.tick + 5 < applyNow) return true; // long ago (starting from a checkpoint)
		if (SoundLog.targetGone(level, e.data)) return true;
		Entity target = null;
		try { target = level.getEntity(UUID.fromString(e.data.getStringOr("target", ""))); } catch (IllegalArgumentException ignored) {}
		net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(e.data.getDoubleOr("x", e.pos.getX() + 0.5),
			e.data.getDoubleOr("y", e.pos.getY()), e.data.getDoubleOr("z", e.pos.getZ() + 0.5));
		if (target == null || target.isRemoved() || target.distanceToSqr(at) > 16) {
			target = null;
			double best = 4;
			for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(2),
				l -> l.isAlive() && !(l instanceof Player))) {
				double d = l.distanceToSqr(at);
				if (d < best) { best = d; target = l; }
			}
		}
		if (target == null) return true;
		int action = e.data.getBooleanOr("magic", false) ? net.minecraft.network.protocol.game.ClientboundAnimatePacket.MAGIC_CRITICAL_HIT
			: net.minecraft.network.protocol.game.ClientboundAnimatePacket.CRITICAL_HIT;
		level.getChunkSource().sendToTrackingPlayers(target, new net.minecraft.network.protocol.game.ClientboundAnimatePacket(target, action));
		return true;
	}

	/** A player's raid omen turned into a raid at this spot (see RaidStartMixin). */
	public static void onRaidStarted(ServerPlayer player, BlockPos pos, int omen) {
		if (player instanceof com.mt1006.mocap.utils.FakePlayer || ReplayScope.depth > 0 || !isRecording(player)) return;
		CompoundTag data = new CompoundTag();
		data.putInt("omen", omen);
		addEffect(player, KIND_RAID, player.level(), pos.immutable(), data);
	}

	/**
	 * The raid starts again: the nearest clone gets the raid omen it had and sets it off, so the raid spawns its waves and
	 * shows its bar like the first time. Clones then take over the raiders they fought (see MobTies.standIn).
	 */
	private static boolean replayRaid(ServerLevel level, Edit e) {
		if (e.tick + 40 < applyNow) return true; // long ago (starting from a checkpoint)
		// several clones of the same player started it at the same moment: it only starts once
		if (level.getRaidAt(e.pos) != null) return true;
		ServerPlayer clone = null;
		double best = 128 * 128;
		for (ServerPlayer p : level.players()) {
			if (!(p instanceof com.mt1006.mocap.utils.FakePlayer) || p.isRemoved()) continue;
			double d = p.distanceToSqr(e.pos.getX(), e.pos.getY(), e.pos.getZ());
			if (d < best) { best = d; clone = p; }
		}
		if (clone == null) return true;
		int omen = Math.max(0, e.data.getIntOr("omen", 0));
		clone.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RAID_OMEN, 20, omen));
		level.getRaids().createOrExtendRaid(clone, e.pos);
		clone.removeEffect(net.minecraft.world.effect.MobEffects.RAID_OMEN);
		return true;
	}

	/** Kind of the edit noting that the night was skipped while this player slept. */
	public static final int KIND_NIGHT_SKIP = 11;
	/** The timeline tick a night skip was last replayed at (several sleeping players note the same one). */
	private static long lastNightSkip = Long.MIN_VALUE;

	/** The night is being skipped: every recording player asleep in this level notes it. */
	public static void onNightSkip(ServerLevel level) {
		if (ReplayScope.depth > 0) return;
		for (ServerPlayer p : level.players()) {
			if (p.isSleeping() && isRecording(p)) addEffect(p, KIND_NIGHT_SKIP, level, BlockPos.ZERO, new CompoundTag());
		}
	}

	/** Skips the night like sleeping does: the clock moves to the wake-up time, and rain stops. */
	private static boolean replayNightSkip(ServerLevel level, Edit e) {
		if (e.tick + 10 < applyNow || lastNightSkip == e.tick) return true; // catching up, or already done for this moment
		lastNightSkip = e.tick;
		var clock = level.dimensionType().defaultClock();
		if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME) && clock.isPresent())
			level.getServer().clockManager().moveToTimeMarker(clock.get(), net.minecraft.world.clock.ClockTimeMarkers.WAKE_UP_FROM_SLEEP);
		if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER) && level.isRaining()) level.resetWeatherCycle();
		return true;
	}

	/** A sound or level event of this player's (see SoundLog). */
	static void addEffect(ServerPlayer player, int kind, ServerLevel level, BlockPos pos, CompoundTag data) {
		net.minecraft.world.entity.Mob target = SoundLog.attackTarget();
		if (target != null && !data.contains("tgt")) data.putString("tgt", target.getUUID().toString()); // part of hitting it
		List<Edit> list = CURRENT.get(player.getUUID());
		if (list != null) list.add(new Edit(lastNow + 1, level.dimension().identifier().toString(), kind, pos, null, data));
	}

	/** The player changed this block: record what it becomes, and its future growth belongs to their run. */
	private static void touch(Player player, ServerLevel level, BlockPos pos) {
		RandomTicks.touch(player.getUUID(), level, pos);
		PENDING_BLOCKS.add(new PendingBlock(player.getUUID(), level, pos.immutable()));
	}

	// everything a player does to blocks

	/**
	 * While the server handles one of a recording player's actions, such as a click, breaking a block or using an item,
	 * every block change it causes is collected here. That includes whole trees grown with bone meal.
	 * At the end of the tick they become one edit (kind 4) per level: each block with its state before and after.
	 */
	private static ServerPlayer actor;
	private static int actorDepth;
	private static final Map<UUID, Map<ServerLevel, it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockState[]>>> ACTOR_CHANGES = new HashMap<>();

	/** The recording player whose action the server is handling right now, or null. */
	public static ServerPlayer currentActor() { return actor; }

	public static void beginAction(ServerPlayer player) {
		if (!recording(player) || !player.level().getServer().isSameThread()) return;
		if (actorDepth++ == 0) actor = player;
	}

	public static void endAction(ServerPlayer player) {
		if (actor == null || actor != player) return;
		if (--actorDepth <= 0) { actorDepth = 0; actor = null; }
	}

	public static void beforeSetBlock(Level level, BlockPos pos) {
		if (actor == null || !(level instanceof ServerLevel sl)) return;
		noteJobSite(level, pos, level.getBlockState(pos));
		var changes = ACTOR_CHANGES.computeIfAbsent(actor.getUUID(), k -> new HashMap<>())
			.computeIfAbsent(sl, k -> new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>());
		long p = pos.asLong();
		if (!changes.containsKey(p)) changes.put(p, new BlockState[] { level.getBlockState(pos), null });
	}

	public static void afterSetBlock(Level level, BlockPos pos) {
		if (actor == null || !(level instanceof ServerLevel sl)) return;
		noteJobSite(level, pos, level.getBlockState(pos));
		var byLevel = ACTOR_CHANGES.get(actor.getUUID());
		var changes = byLevel != null ? byLevel.get(sl) : null;
		BlockState[] s = changes != null ? changes.get(pos.asLong()) : null;
		if (s != null) s[1] = level.getBlockState(pos);
	}

	private static void flushActions(long now) {
		actor = null; actorDepth = 0; // safety: never let a capture leak into the next tick
		for (var byPlayer : ACTOR_CHANGES.entrySet()) {
			List<Edit> list = CURRENT.get(byPlayer.getKey());
			for (var byLevel : byPlayer.getValue().entrySet()) {
				ServerLevel level = byLevel.getKey();
				ListTag changes = new ListTag();
				for (var e : byLevel.getValue().long2ObjectEntrySet()) {
					BlockState[] s = e.getValue();
					if (s[1] == null || s[0] == s[1]) continue;
					BlockPos pos = BlockPos.of(e.getLongKey());
					RandomTicks.touch(byPlayer.getKey(), level, pos); // its growth now follows this run
					CompoundTag c = new CompoundTag();
					c.putLong("p", e.getLongKey());
					c.put("o", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, s[0]).getOrThrow());
					c.put("n", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, s[1]).getOrThrow());
					BlockEntity be = level.getBlockEntity(pos);
					boolean keepsContents = be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity && s[0].getBlock() == s[1].getBlock();
					if (be != null && !keepsContents && level.getBlockState(pos) == s[1]) c.put("be", be.saveWithFullMetadata(level.registryAccess()));
					changes.add(c);
				}
				if (list == null || changes.isEmpty()) continue;
				CompoundTag data = new CompoundTag();
				data.put("c", changes);
				BlockPos first = BlockPos.of(changes.getCompoundOrEmpty(0).getLongOr("p", 0));
				list.add(new Edit(now, level.dimension().identifier().toString(), 4, first, null, data));
			}
		}
		ACTOR_CHANGES.clear();
	}

	/** Replays a player's block changes one by one, each only where the block is still what it was before. */
	private static void applyActions(ServerLevel level, CompoundTag data) {
		// Place every block of the action first and only then run block updates: blocks that need each other (a nether
		// portal, a door's two halves...) would otherwise break as soon as the first one is placed next to air.
		List<BlockPos> placed = new ArrayList<>();
		for (Tag t : data.getListOrEmpty("c")) {
			if (!(t instanceof CompoundTag c)) continue;
			BlockPos pos = BlockPos.of(c.getLongOr("p", 0));
			BlockState old = BlockState.CODEC.parse(NbtOps.INSTANCE, c.get("o")).result().orElse(null);
			BlockState after = BlockState.CODEC.parse(NbtOps.INSTANCE, c.get("n")).result().orElse(null);
			if (old == null || after == null) continue;
			BlockState now = level.getBlockState(pos);
			// Already done (e.g. by the clone's own replay) or changed since. Except: the clone's replayed flint and steel may
			// have left plain fire where the portal was lit (if the frame wasn't finished yet at that moment); the portal
			// still goes there, or that fire would burn out and take the whole portal down with it.
			boolean fireInPortal = now.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock
				&& after.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);
			if (now != old && !fireInPortal) continue;
			level.setBlock(pos, after, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			placed.add(pos);
			boolean sameBlock = old.getBlock() == after.getBlock();
			c.getCompound("be").ifPresent(tag -> {
				BlockEntity be = level.getBlockEntity(pos);
				if (be == null || (sameBlock && be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity)) return;
				be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
				be.setChanged();
				level.sendBlockUpdated(pos, after, after, Block.UPDATE_ALL);
			});
		}
		for (BlockPos pos : placed) {
			BlockState state = level.getBlockState(pos);
			level.updateNeighborsAt(pos, state.getBlock(), null);
			state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
			afterReplayPlace(level, pos);
		}
	}

	/**
	 * A replayed button, pressure plate or tripwire is put back pressed, but nothing would ever release it: in the original
	 * run that happened in a scheduled tick a moment later. Schedule it again (plates and tripwires then check whether
	 * anyone is still on them, like normal), and pass its redstone signal on to the blocks it powers.
	 */
	private static void afterReplayPlace(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		int delay = -1;
		if (block instanceof net.minecraft.world.level.block.ButtonBlock && state.getValue(BlockStateProperties.POWERED)) delay = buttonTicks(block);
		else if (block instanceof net.minecraft.world.level.block.TargetBlock && state.getValue(BlockStateProperties.POWER) > 0) delay = 20;
		else if (block instanceof net.minecraft.world.level.block.WeightedPressurePlateBlock && state.getValue(BlockStateProperties.POWER) > 0) delay = 10;
		else if (block instanceof net.minecraft.world.level.block.PressurePlateBlock && state.getValue(BlockStateProperties.POWERED)) delay = 20;
		else if ((block instanceof net.minecraft.world.level.block.TripWireBlock || block instanceof net.minecraft.world.level.block.TripWireHookBlock)
			&& state.getValue(BlockStateProperties.POWERED)) delay = 10;
		if (delay > 0 && !level.getBlockTicks().hasScheduledTick(pos, block)) level.scheduleTick(pos, block, delay);
		if (state.isSignalSource()) {
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) level.updateNeighborsAt(pos.relative(d), block, null);
		}
	}

	private static int buttonTicks(Block button) {
		try {
			var f = net.minecraft.world.level.block.ButtonBlock.class.getDeclaredField("ticksToStayPressed");
			f.setAccessible(true);
			return f.getInt(button);
		} catch (Exception e) {
			return 20;
		}
	}

	/** A recorded random tick (see RandomTicks): kind 3, the exact block changes with their previous states. */
	static void addRandomTickEdit(UUID owner, ServerLevel level, BlockPos origin, CompoundTag data) {
		List<Edit> list = CURRENT.get(owner);
		if (list != null) list.add(new Edit(lastNow, level.dimension().identifier().toString(), 3, origin, null, data));
	}

	public static void onBlockBroken(Player player, Level level, BlockPos pos, BlockState state) {
		if (!recording(player) || !(level instanceof ServerLevel sl)) return;
		touch(player, sl, pos);
		BROKEN_WAS.put(new PendingBlock(player.getUUID(), sl, pos.immutable()), state);
		touch(player, sl, pos.above());
	}

	/** Captures anything still pending (and any open container) and hands back the player's edits for this run. */
	public static List<Edit> finish(ServerPlayer player, long now) {
		if (player.containerMenu != player.inventoryMenu) onContainerClosed(player);
		flushPending(now);
		ContainerDeltas.discard(player.getUUID());
		List<Edit> edits = CURRENT.remove(player.getUUID());
		RandomTicks.forget(player.getUUID());
		return edits != null ? edits : new ArrayList<>();
	}

	public static boolean isRecording(Player player) { return recording(player); }

	private static boolean recording(Player player) {
		return player instanceof ServerPlayer && CURRENT.containsKey(player.getUUID());
	}

	// capture hooks

	/** Right click on a block: capture what it (and the spot in front of it, where a block may be placed) becomes. */
	public static void onUseBlock(Player player, Level level, BlockPos pos, net.minecraft.core.Direction face) {
		if (!recording(player) || !(level instanceof ServerLevel sl)) return;
		touch(player, sl, pos);
		touch(player, sl, pos.relative(face));
		BlockState state = sl.getBlockState(pos);
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
			PENDING_BLOCKS.add(new PendingBlock(player.getUUID(), sl, other.immutable()));
		}
	}

	/** Right click or hit on a non-living entity (item frame, armor stand, chest boat...): capture it at the end of the tick. */
	public static void onEntityInteract(Player player, Entity entity) {
		if (!recording(player)) return;
		EntityOwners.touch(player, entity); // hitting/using a boat, armor stand... makes it part of your recording
		if (entity instanceof Player) return; // players (and clones) aren't world state
		PENDING_ENTITIES.add(new PendingEntity(player.getUUID(), entity));
	}

	public static void onSignEdited(Player player, BlockEntity sign) {
		if (!recording(player) || !(sign.getLevel() instanceof ServerLevel sl)) return;
		PENDING_BLOCKS.add(new PendingBlock(player.getUUID(), sl, sign.getBlockPos().immutable()));
	}

	/** A block entity a player changed without changing the block (a beacon's effects). */
	public static void onBlockEntityChanged(Player player, Level level, BlockPos pos) {
		if (!recording(player) || !(level instanceof ServerLevel sl)) return;
		PENDING_BLOCKS.add(new PendingBlock(player.getUUID(), sl, pos.immutable()));
	}

	/** A player hurt an entity, so its state (or its death) is recorded at the end of the tick. */
	public static void onEntityHurt(Entity victim, net.minecraft.world.damagesource.DamageSource source, float taken) {
		if (victim instanceof Player) return;
		if (!(source.getEntity() instanceof ServerPlayer player)) return;
		if (BossReplay.isBoss(victim) || TrialSpawns.held(victim)) {
			if (player instanceof com.mt1006.mocap.utils.FakePlayer) return;
			// a trial spawner's mob a clone is fighting keeps what players did to it (see TrialSpawns)
			if (TrialSpawns.held(victim)) TrialSpawns.addDamage(victim, taken);
			// a boss keeps the damage done to it, so it adds up with what other players do in later runs
			boolean ranged = source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
				|| source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
			if (taken > 0 && recording(player)) BOSS_DAMAGE.merge(new BossHit(player.getUUID(), victim, ranged), taken, Float::sum);
			return;
		}
		onEntityInteract(player, victim);
	}

	/** A container screen is closing: record every block/entity container behind its slots. */
	public static void onContainerClosed(ServerPlayer player) {
		if (!recording(player)) return;
		// Trading changes the villager (uses, experience, level): record the villager too.
		for (var trader : player.level().getEntitiesOfClass(net.minecraft.world.entity.npc.villager.AbstractVillager.class, player.getBoundingBox().inflate(16))) {
			if (trader.getTradingPlayer() == player) PENDING_ENTITIES.add(new PendingEntity(player.getUUID(), trader));
		}
		// What went into / out of containers is recorded click by click as items added and removed (ContainerDeltas),
		// never as the whole inventory, so a clone doesn't overwrite what other players changed in there since.
	}

	/** End of every server tick while running: turn pending captures into edits. */
	public static void flushPending(long now) {
		lastNow = now;
		setBlockDepth = 0; // This resets the counter in case a block change ever threw an exception half-way.
		flushActions(now);
		ContainerDeltas.flush(now, (player, edit) -> {
			List<Edit> list = CURRENT.get(player);
			if (list != null) list.add(edit);
		});
		for (PendingBlock p : PENDING_BLOCKS) {
			List<Edit> list = CURRENT.get(p.player);
			if (list == null) continue;
			CompoundTag data = captureBlock(p.level, p.pos);
			if (p.src != null) data.putString("src", p.src.toString());
			BlockState was = BROKEN_WAS.get(p);
			if (was != null) data.put("was", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, was).getOrThrow());
			list.add(new Edit(now, p.level.dimension().identifier().toString(), 0, p.pos, null, data));
		}
		PENDING_BLOCKS.clear();
		BROKEN_WAS.clear();
		for (PendingEntity p : PENDING_ENTITIES) {
			List<Edit> list = CURRENT.get(p.player);
			if (list == null) continue;
			Entity e = p.entity;
			String dim = e.level().dimension().identifier().toString();
			if (e.isRemoved()) list.add(new Edit(now, dim, 2, e.blockPosition(), e.getUUID(), new CompoundTag()));
			else list.add(new Edit(now, dim, 1, e.blockPosition(), e.getUUID(), captureEntity(e)));
		}
		PENDING_ENTITIES.clear();
		for (var entry : BOSS_DAMAGE.entrySet()) {
			List<Edit> list = CURRENT.get(entry.getKey().player);
			Entity e = entry.getKey().entity;
			if (list == null) continue;
			CompoundTag data = new CompoundTag();
			data.putFloat("bossDamage", entry.getValue());
			if (entry.getKey().ranged) data.putBoolean("ranged", true);
			data.putString("type", net.minecraft.world.entity.EntityType.getKey(e.getType()).toString());
			list.add(new Edit(now, e.level().dimension().identifier().toString(), 1, e.blockPosition(), e.getUUID(), data));
		}
		BOSS_DAMAGE.clear();
	}

	private static CompoundTag captureBlock(ServerLevel level, BlockPos pos) {
		CompoundTag t = new CompoundTag();
		t.put("state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, level.getBlockState(pos)).getOrThrow());
		BlockEntity be = level.getBlockEntity(pos);
		// Container contents are recorded as item changes instead (see ContainerDeltas).
		if (be != null && !(be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity)) t.put("be", be.saveWithFullMetadata(level.registryAccess()));
		return t;
	}

	private static CompoundTag captureEntity(Entity e) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
		e.saveWithoutId(out);
		return out.buildResult();
	}

	// replay

	/** Called when a clone starts playing: queue its edits (ones before `now` are applied right away). */
	public static void schedule(MinecraftServer server, List<Edit> edits, long now) {
		// In timed resets, what clones take out of containers is gone from the start, and what they put in arrives later.
		if (TimedReset.antiDupe()) edits = Reservations.prepare(edits);
		List<Edit> added = new ArrayList<>(edits.size());
		for (Edit e : edits) {
			added.add(e);
			RaidWaves.scheduled(e);
			TrialSpawns.scheduled(e);
			if (e.kind == 0) RandomTicks.claim(e.dim, e.pos.asLong());
			if (e.kind == 3 || e.kind == 4) {
				for (Tag t : e.data.getListOrEmpty("c")) if (t instanceof CompoundTag c) RandomTicks.claim(e.dim, c.getLongOr("p", 0));
			}
		}
		// Only the new ones are sorted, then merged in: with dozens of clones starting at once, sorting everything again
		// for each one was slow. (Ties keep what was queued first in front, like a stable sort of the lot.)
		added.sort((a, b) -> Long.compare(a.tick, b.tick));
		java.util.ArrayDeque<Edit> merged = new java.util.ArrayDeque<>(SCHEDULED.size() + added.size());
		int i = 0;
		for (Edit old : SCHEDULED) {
			while (i < added.size() && added.get(i).tick < old.tick) merged.add(added.get(i++));
			merged.add(old);
		}
		while (i < added.size()) merged.add(added.get(i++));
		SCHEDULED = merged;
		apply(server, now);
	}

	/** Applies the edits that are due (the queue is sorted by time, so only its front is ever looked at). */
	public static void apply(MinecraftServer server, long now) {
		// Items these changes knock loose (or loot of mobs they kill) are already in the clones' recordings.
		ReplayScope.depth++;
		try { applyDue(server, now); }
		finally { ReplayScope.depth--; }
	}

	private static void applyDue(MinecraftServer server, long now) {
		applyNow = now;
		// Timed resets retry often, because a container that a clone empties must be emptied before anyone can open it.
		if ((now % 20 == 0 || (TimedReset.isActive() && now % 2 == 0)) && !RETRY.isEmpty()) RETRY.removeIf(e -> tryApply(server, e));
		while (!SCHEDULED.isEmpty() && SCHEDULED.peekFirst().tick + APPLY_DELAY <= now) {
			Edit e = SCHEDULED.pollFirst();
			if (!tryApply(server, e)) RETRY.add(e); // unloaded chunk or entity not loaded yet: try again until the run ends
		}
	}

	private static int replayFailures;

	private static boolean tryApply(MinecraftServer server, Edit e) {
		ServerLevel level = server.getLevel(DIMS.computeIfAbsent(e.dim, d -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(d))));
		if (level == null) return true;
		try {
			return applyOne(level, e);
		} catch (Exception ex) {
			if (replayFailures++ < 3) Recur.LOGGER.warn("Could not replay a world change at {}", e.pos, ex); // once is enough to see why

			return true;
		}
	}

	/** A recording player went to another dimension (kind 6): their clone does too, at that moment. */
	public static void addDimensionChange(ServerPlayer player, long tick, String to) {
		List<Edit> list = CURRENT.get(player.getUUID());
		if (list == null) return;
		// Leaving the End can be reported twice.
		for (int i = list.size() - 1; i >= 0 && list.get(i).tick == tick; i--)
			if (list.get(i).kind == 6 && to.equals(list.get(i).data.getStringOr("to", ""))) return;
		CompoundTag data = new CompoundTag();
		data.putString("to", to);
		list.add(new Edit(tick, to, 6, BlockPos.ZERO, null, data));
	}

	/**
	 * What a removed clone (a ghost) still does in timed resets. It only takes things: items out of containers, the
	 * containers it breaks, what it takes from item frames and armor stands, and entities it kills. Only edits after
	 * `after` are included.
	 */
	public static List<Edit> ghostEdits(List<Edit> edits, long after) {
		List<Edit> out = new ArrayList<>();
		for (Edit e : edits) {
			if (e.tick <= after) continue;
			CompoundTag data;
			if (e.kind == 5) {
				ListTag takes = new ListTag();
				for (Tag t : e.data.getListOrEmpty("items")) if (t instanceof CompoundTag c && c.getIntOr("n", 0) < 0) takes.add(c.copy());
				if (takes.isEmpty()) continue;
				data = e.data.copy();
				data.put("items", takes);
			} else if (e.kind == 0 || e.kind == 1 || e.kind == 2) {
				data = e.data.copy();
			} else {
				continue;
			}
			data.putBoolean("recur_ghost", true);
			out.add(new Edit(e.tick, e.dim, e.kind, e.pos, e.entity, data));
		}
		return out;
	}

	/** Above 0 inside Level.setBlock. Anything a block creates as it is placed, like a golem or the Wither, comes back with the block. */
	public static int setBlockDepth;

	/**
	 * An entity appeared during a recording player's action (or their projectile's hit): a mob from a spawn egg or a
	 * bucket, an egg's chick, Channeling lightning, a lingering potion's cloud... Recorded (kind 8), so their clone makes
	 * it again. Items, experience, projectiles and what the clone's recording moves itself aren't (they're replayed
	 * already), nor what a placed block makes (the block makes it again).
	 */
	public static void onEntitySpawned(Entity e) {
		if (actor == null || ReplayScope.depth > 0 || setBlockDepth > 0 || EntityRestore.isQuiet()) return;
		boolean wanted = e instanceof net.minecraft.world.entity.LightningBolt || e instanceof net.minecraft.world.entity.AreaEffectCloud
			|| (e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof net.minecraft.world.entity.animal.golem.AbstractGolem)
				&& !(e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) && !(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
				&& !EntityOwners.trackable(e)
				// These come by themselves when the clone repeats the cause (a slime it kills splits, a block it mines
				// lets silverfish out, a zombie it hits calls for help): recording them too would make them twice.
				&& !(e instanceof net.minecraft.world.entity.monster.cubemob.Slime) && !(e instanceof net.minecraft.world.entity.monster.Silverfish)
				&& !(e instanceof net.minecraft.world.entity.monster.zombie.Zombie));
		if (!wanted) return;
		List<Edit> list = CURRENT.get(actor.getUUID());
		if (list == null) return;
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
		if (!e.save(out)) return;
		list.add(new Edit(lastNow + 1, e.level().dimension().identifier().toString(), 8, e.blockPosition(), null, out.buildResult()));
	}

	/** A recording player started the dragon respawn (kind 7): their clone starts it again at that moment. */
	public static void onDragonRespawn(ServerLevel level) {
		if (actor == null || ReplayScope.depth > 0) return;
		List<Edit> list = CURRENT.get(actor.getUUID());
		if (list != null) list.add(new Edit(lastNow + 1, level.dimension().identifier().toString(), 7, BlockPos.ZERO, null, new CompoundTag()));
	}

	/** A clone was removed for good: drop the world changes of it that haven't happened yet. */
	public static void unschedule(List<Edit> edits) {
		// by identity: comparing edits by value compares all their NBT, which stalled the server when a clone died
		Set<Edit> exact = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		exact.addAll(edits);
		// Timed resets mark copies of some entity edits, so those are matched by their tick, kind and entity.
		Set<String> loose = new java.util.HashSet<>();
		for (Edit e : edits) if (e.entity != null) loose.add(e.tick + "|" + e.kind + "|" + e.entity);
		java.util.function.Predicate<Edit> mine = e -> exact.contains(e) || (e.entity != null && loose.contains(e.tick + "|" + e.kind + "|" + e.entity));
		java.util.function.Predicate<Edit> drop = e -> {
			if (!mine.test(e)) return false;
			if (e.kind == 5 || e.kind == 0) Reservations.released(e); // what it would have taken / broken isn't locked any more
			return true;
		};
		SCHEDULED.removeIf(drop);
		RETRY.removeIf(drop);
		RaidWaves.unscheduled(mine);
		TrialSpawns.unscheduled(mine);
	}

	public static void clearScheduled() { SCHEDULED.clear(); RETRY.clear(); RaidWaves.clear(); TrialSpawns.clear(); TrialVaults.clear(); RandomTicks.clearClaims(); Reservations.clear(); }

	/** Timeline tick being applied right now. Edits much older than it are catching up (starting from a checkpoint). */
	private static long applyNow;

	private static boolean applyOne(ServerLevel level, Edit e) {
		boolean timed = TimedReset.antiDupe();
		if (e.kind == 6) return true; // A clone going through a portal is moved by LoopManager.
		if (e.kind == SoundLog.KIND_SOUND || e.kind == SoundLog.KIND_LEVEL_EVENT || e.kind == SoundLog.KIND_PARTICLES) return SoundLog.play(level, e, applyNow);
		if (e.kind == KIND_NIGHT_SKIP) return replayNightSkip(level, e);
		if (e.kind == 8) {
			// Something their action made (a chick, lightning, a mob from a spawn egg...): made again, as a new entity.
			Entity made = net.minecraft.world.entity.EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), e.data),
				level, net.minecraft.world.entity.EntitySpawnReason.LOAD, net.minecraft.world.entity.EntityProcessor.NOP);
			if (made != null) {
				// It keeps its UUID, so a recording that moves it later (a mob tied to its player) finds it. If that UUID
				// is still taken, it gets a new one.
				if (!level.addFreshEntity(made)) {
					made.getSelfAndPassengers().forEach(x -> x.setUUID(UUID.randomUUID()));
					level.addFreshEntity(made);
				}
			}
			return true;
		}
		if (e.kind == KIND_RAID) return replayRaid(level, e);
		if (e.kind == RaidWaves.KIND) return RaidWaves.replay(level, e, applyNow);
		if (e.kind == TrialSpawns.KIND) return TrialSpawns.replay(level, e, applyNow);
		if (e.kind == KIND_CRIT) return replayCrit(level, e);
		if (e.kind == KIND_EXPLOSION) return replayExplosion(level, e);
		// broken by a mob's explosion, and that mob left its replay: it hasn't blown up (yet)
		if (e.kind == 0 && e.data.contains("src")) {
			Entity src = sourceOf(level, e.data);
			// (a wither always blows up: its crater always comes)
			if (src != null && !(src instanceof net.minecraft.world.entity.boss.wither.WitherBoss) && com.mt1006.mocap.mocap.playing.Controlled.hasLeft(src)) return true;
		}
		if (e.kind == 7) {
			// Its End crystals are back on the exit portal by now (the clone placed them): respawn the dragon.
			if (level.getDragonFight() != null) level.getDragonFight().tryRespawn();
			return true;
		}
		boolean ghost = e.data.getBooleanOr("recur_ghost", e.data.getBooleanOr(Legacy.old("recur_ghost"), false));
		if (ghost && e.kind == 0) {
			// A removed clone only still breaks containers (and takes what's in them); other blocks stay.
			Reservations.released(e);
			if (!(level.getBlockEntity(e.pos) instanceof Container c)) return true;
			c.clearContent();
			level.setBlock(e.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			return true;
		}
		if (e.kind == 5) {
			if (timed) Reservations.released(e);
			return ContainerDeltas.apply(level, e);
		}
		if (e.kind == 3) { RandomTicks.apply(level, e.data); return true; }
		if (e.kind == 4) { applyActions(level, e.data); return true; }
		if (e.kind == 0) {
			if (timed) Reservations.released(e); // a container it breaks now stops being locked
			// If a different block is now where its player broke one, the clone leaves it alone.
			if (e.data.contains("was")) {
				BlockState was = BlockState.CODEC.parse(NbtOps.INSTANCE, e.data.get("was")).result().orElse(null);
				if (was != null && level.getBlockState(e.pos).getBlock() != was.getBlock()
					&& level.getBlockState(e.pos).getBlock() != BlockState.CODEC.parse(NbtOps.INSTANCE, e.data.get("state")).result().map(BlockState::getBlock).orElse(null))
					return true;
			}
			BlockState recorded = BlockState.CODEC.parse(NbtOps.INSTANCE, e.data.get("state")).result().orElse(Blocks.AIR.defaultBlockState());
			BlockState state = recorded;
			// Keep connections (double chests, fences, stairs...) consistent with what is next to it now.
			state = Block.updateFromNeighbourShapes(state, level, e.pos);
			if (level.getBlockState(e.pos) != state) {
				level.setBlock(e.pos, state, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
				afterReplayPlace(level, e.pos);
			}
			final BlockState placed = state;
			e.data.getCompound("be").ifPresent(tag -> {
				BlockEntity be = level.getBlockEntity(e.pos);
				if (be == null || be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity) return;
				be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
				be.setChanged();
				level.sendBlockUpdated(e.pos, placed, placed, Block.UPDATE_ALL);
			});
			return true;
		}
		Entity entity = level.getEntity(e.entity);
		// A mob born during the loop has another id in every run: the one the clone moves in its place.
		if (entity == null && e.entity != null) entity = com.mt1006.mocap.mocap.playing.Controlled.alias(e.entity);
		// A boss is often a different one in every run (a dragon in an End made during the loop, a wither a clone builds):
		// the damage goes to the nearest one of the same kind.
		if (entity == null && e.kind == 1 && e.data.contains("bossDamage")) entity = BossReplay.nearestOfType(level, e.data.getStringOr("type", ""), e.pos);
		// The End's crystals are new ones whenever its chunks were made again: the one on that pillar.
		if (entity == null && e.kind == 2) entity = BossReplay.crystalAt(level, Vec3.atBottomCenterOf(e.pos));
		if (entity == null) return false;
		if (timed && Reservations.applyHolderEdit(level, entity, e)) return true;
		// It left its replay to fight a player for real: what the clone did to it (hits, the kill) doesn't happen any more.
		if ((e.kind == 1 || e.kind == 2) && !e.data.contains("bossDamage") && com.mt1006.mocap.mocap.playing.Controlled.hasLeft(entity)) return true;
		// A removed clone doesn't move or change things any more, it only kills what it killed.
		if (ghost && e.kind == 1 && !(e.data.contains("Health") && e.data.getFloatOr("Health", 1f) <= 0)) return true;
		if (e.kind == 1 && e.data.contains("bossDamage")) {
			// once a wither is down to half health its armour stops arrows, even if in the recording it wasn't yet
			if (e.data.getBooleanOr("ranged", false) && entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss w && w.isPowered()) return true;
			if (entity instanceof LivingEntity boss) BossReplay.damage(level, boss, e.data.getFloatOr("bossDamage", 0f), applyNow - e.tick <= 40);
			return true;
		}
		if (e.kind == 2) {
			if (timed) entity.addTag(CloneMade.TAG); // killed by a clone: no loot or experience
			MobDrops.killedByReplay(entity);
			// When the player killed it, a mob dies properly with its animation and drops. Other entities just go away.
			boolean past = applyNow - e.tick > 40;
			if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal && !past) BossReplay.explodeCrystal(level, crystal);
			else if (entity instanceof LivingEntity living && !past) living.kill(level); else entity.discard();
			return true;
		}
		Vec3 pos = entity.position();
		float yRot = entity.getYRot(), xRot = entity.getXRot();
		Container inv = entity instanceof net.minecraft.world.entity.animal.equine.AbstractHorse h
			? ((dev.recur.mixin.AbstractHorseAccessor) h).recur$inventory() : entity instanceof Container c ? c : null;
		List<net.minecraft.world.item.ItemStack> keep = new ArrayList<>();
		if (inv != null) for (int i = 0; i < inv.getContainerSize(); i++) keep.add(inv.getItem(i).copy());
		entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), e.data));
		entity.snapTo(pos.x, pos.y, pos.z, yRot, xRot);
		if (entity instanceof net.minecraft.world.entity.Mob mob) CloneAnger.redirect(mob);
		// Contents are item changes (kind 5), so the entity's own data mustn't overwrite them.
		Container after = entity instanceof net.minecraft.world.entity.animal.equine.AbstractHorse h2
			? ((dev.recur.mixin.AbstractHorseAccessor) h2).recur$inventory() : entity instanceof Container c2 ? c2 : null;
		if (after != null) for (int i = 0; i < after.getContainerSize() && i < keep.size(); i++) after.setItem(i, keep.get(i));
		return true;
	}

	// persistence

	public static ListTag toTag(List<Edit> edits) {
		ListTag list = new ListTag();
		for (Edit e : edits) list.add(e.toTag());
		return list;
	}

	public static List<Edit> fromTag(ListTag list) {
		List<Edit> out = new ArrayList<>();
		for (Tag t : list) if (t instanceof CompoundTag c) out.add(Edit.fromTag(c));
		return out;
	}
}
