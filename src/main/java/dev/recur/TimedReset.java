package dev.recur;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world side of timed resets (LoopManager runs the clones): every few minutes (2 by default) the whole world goes
 * back to how it was when the mode started, but players keep everything (inventory, where they are, health...).
 * Containers reset with everything else. No checkpoints and no loading screen.
 *
 * Like the loop, it keeps the files on disk as the untouched copy of the world (LoopOverlay). A reset has to happen while
 * players are standing in the world, so instead of unloading everything it puts back, in place, only the parts of loaded
 * chunks that changed (it notes every 16x16x16 section a block or block entity changed in). Mobs go back to how they were
 * when they first loaded (reusing the checkpoint entity restore). Unloaded chunks simply load the untouched copy later.
 */
public final class TimedReset {
	public static final int DEFAULT_INTERVAL = 20 * 120;

	private static boolean active;
	private static int intervalTicks = DEFAULT_INTERVAL;
	/** Clear the two blocks a player is standing in after a reset, so nobody suffocates (e.g. while mining). */
	private static boolean clearAtPlayers = true;
	/** All the anti-dupe rules (CloneMade, Reservations): /loop timed antidupe true|false. */
	private static boolean antiDupe = true;
	private static Map<String, Long> clocks = Map.of();
	private static CompoundTag weather = new CompoundTag();

	/** Sections (SectionPos longs) where something changed since the last reset, per level. */
	private static final Map<ServerLevel, LongOpenHashSet> DIRTY = new HashMap<>();
	/** Chunks first generated during this mode (so not on disk): their freshly generated data, compressed. */
	/**
	 * Chunks generated during this mode aren't on disk, so their untouched blocks are kept here instead. Copying
	 * whole chunks as they generate froze the game while exploring, so only a section about to change for the first
	 * time is copied (its block states and block entities: microseconds, and only where something actually changes).
	 */
	private static final Map<ServerLevel, LongOpenHashSet> NEW_CHUNKS = new HashMap<>();
	/** By section (SectionPos.asLong): how that section of a new chunk was before its first change. */
	private static final Map<ServerLevel, Long2ObjectOpenHashMap<SectionCopy>> GENERATED = new HashMap<>();

	private record SectionCopy(LevelChunkSection states, List<CompoundTag> blockEntities) {}

	/** Every entity as it was when it first loaded, per level: what a reset puts back. */
	private static final Map<ServerLevel, Map<UUID, CompoundTag>> FIRST_SEEN = new HashMap<>();
	private static boolean restoring;

	private TimedReset() {}

	public static boolean isActive() { return active; }

	/** Changes are tracked in timed mode and during normal loops (which reset in place too, see restoreInPlace). */
	private static void clearFirstSeen() {
		FIRST_SEEN.clear();
	}

	private static boolean tracking() { return active || LoopOverlay.active; }
	public static boolean isRestoring() { return restoring; }
	public static int intervalTicks() { return intervalTicks; }

	/** A saved timed loop is being resumed but the world-side state was missing: switch this side on as well. */
	public static void forceOn() {
		active = true;
		LoopOverlay.active = true;
	}

	// start / stop

	public static boolean start(MinecraftServer server, ServerPlayer starter) {
		if (active || !WorldGuard.allowed()) return false;
		// Everything on disk right now is the untouched world; from here on writes go to the overlay.
		server.saveEverything(false, true, true);
		LoopOverlay.active = true;
		clocks = WorldReset.captureClocks(server);
		weather = StateRebind.captureWeather(server);
		DIRTY.clear(); GENERATED.clear(); NEW_CHUNKS.clear(); clearFirstSeen(); Graves.clear(); CloneMade.clear(); FRESH_AT.clear();
		for (ServerLevel level : server.getAllLevels()) for (Entity e : level.getAllEntities()) firstSeen(e);
		active = true;
		save(server);
		broadcast(server, Component.literal("Timed resets are on: every " + describe(intervalTicks)
			+ " the world resets and your past selves play again as clones. You keep your stuff."));
		return true;
	}

	/** Turns the mode off and keeps the world as it is now. */
	public static void stop(MinecraftServer server) {
		if (!active) return;
		active = false;
		EntityRestore.clear();
		LoopOverlay.flushAll();
		DIRTY.clear(); GENERATED.clear(); NEW_CHUNKS.clear(); clearFirstSeen(); Graves.clear(); CloneMade.clear(); FRESH_AT.clear();
		save(server);
		broadcast(server, Component.literal("Timed resets are off. The world stays as it is now."));
	}

	/** The server is stopping or another world is loading: forget everything (the files on disk are still untouched). */
	public static void shutdown() {
		active = false;
		restoring = false;
		DIRTY.clear(); GENERATED.clear(); NEW_CHUNKS.clear(); clearFirstSeen(); Graves.clear(); CloneMade.clear(); FRESH_AT.clear();
		clocks = Map.of();
		weather = new CompoundTag();
	}

	public static void setInterval(MinecraftServer server, int ticks) {
		intervalTicks = Math.max(20 * 10, ticks);
		save(server);
		LoopManager.syncAll(server);
		ServerSettings.syncAll(server);
	}

	public static void setClearAtPlayers(MinecraftServer server, boolean value) {
		clearAtPlayers = value;
		save(server);
		ServerSettings.syncAll(server);
	}

	public static boolean clearAtPlayers() { return clearAtPlayers; }

	/** The anti-dupe rules apply: timed resets with them on, or a normal loop with them on (see ServerSettings). */
	public static boolean antiDupe() {
		if (active) return antiDupe;
		return ServerSettings.loopAntiDupe && LoopManager.isActive() && !LoopManager.isTimed();
	}

	public static boolean antiDupeSetting() { return antiDupe; }

	public static void setAntiDupe(MinecraftServer server, boolean value) {
		antiDupe = value;
		save(server);
		ServerSettings.syncAll(server);
	}

	// tracking

	/** A block or block entity changed (Level.setBlock / BlockEntity.setChanged). */
	public static void onChanged(Level level, BlockPos pos) {
		if (!tracking() || restoring || !(level instanceof ServerLevel sl)) return;
		DIRTY.computeIfAbsent(sl, k -> new LongOpenHashSet()).add(SectionPos.asLong(
			SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ())));
	}

	/** A chunk loaded: new ones are noted (see beforeChange); graves inside it keep their air gap. */
	public static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
		if (!tracking()) return;
		if (generated) NEW_CHUNKS.computeIfAbsent(level, k -> new LongOpenHashSet()).add(chunk.getPos().pack());
		if (active) Graves.onChunkLoad(level, chunk.getPos());
	}

	/** A block is about to change: if it's in a chunk generated during this mode, copy its section as it still is (once). */
	public static void beforeChange(Level level, BlockPos pos) {
		if (!tracking() || restoring || !(level instanceof ServerLevel sl)) return;
		var fresh = NEW_CHUNKS.get(sl);
		if (fresh == null) return;
		int cx = SectionPos.blockToSectionCoord(pos.getX()), cz = SectionPos.blockToSectionCoord(pos.getZ());
		if (!fresh.contains(ChunkPos.pack(cx, cz))) return;
		int sy = SectionPos.blockToSectionCoord(pos.getY());
		long key = SectionPos.asLong(cx, sy, cz);
		var copies = GENERATED.computeIfAbsent(sl, k -> new Long2ObjectOpenHashMap<>());
		if (copies.containsKey(key)) return;
		LevelChunk chunk = sl.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) return;
		int index = chunk.getSectionIndexFromSectionY(sy);
		if (index < 0 || index >= chunk.getSections().length) return;
		List<CompoundTag> bes = new ArrayList<>();
		for (BlockEntity be : chunk.getBlockEntities().values())
			if (SectionPos.blockToSectionCoord(be.getBlockPos().getY()) == sy) bes.add(be.saveWithFullMetadata(sl.registryAccess()));
		copies.put(key, new SectionCopy(chunk.getSection(index).copy(), bes));
	}

	/** An entity appeared: the first time, remember how it was (what resets put back). Brand-new ones aren't kept. */
	public static void onEntityLoad(Entity e) {
		// (what EntityRestore puts back is how it was at the loop's start, so it's remembered too)
		if (!tracking() || restoring || EntityRestore.addingFresh > 0) return;
		firstSeen(e);
	}

	private static void firstSeen(Entity e) {
		if (e instanceof Player || e.isPassenger() || e.isRemoved() || Graves.isGraveItem(e) || !(e.level() instanceof ServerLevel level)) return;
		// Made during the loop (a dropped item that then went through a portal arrives as a "loaded" entity): not part of
		// the world as it was, so it isn't put back by resets.
		if (com.mt1006.mocap.mocap.recording.Recording.FRESH_ENTITIES.contains(e.getUUID())) return;
		Map<UUID, CompoundTag> seen = FIRST_SEEN.computeIfAbsent(level, k -> new HashMap<>());
		if (seen.containsKey(e.getUUID())) return;
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		if (e.save(out)) seen.put(e.getUUID(), out.buildResult());
	}

	// ticking

	/** (The countdown itself is the run's timer in LoopManager.) */
	public static void tick(MinecraftServer server) {
		CloneMade.tick();
		if (!active) return;
		Graves.tick(server);
	}

	/** Puts the world back in place (LoopManager has already stopped the clones and saved the recordings). */
	public static void reset(MinecraftServer server) {
		if (!active) return;
		long started = System.nanoTime();
		// Push pending chunk writes into the overlay, then drop the overlay: the files are the untouched world again.
		server.saveEverything(true, true, true);
		LoopOverlay.clearAll();
		StateRebind.rebindAll(server);
		restoring = true;
		int sections = 0;
		try {
			for (ServerLevel level : server.getAllLevels()) {
				for (ServerPlayer p : level.players()) if (p.containerMenu != p.inventoryMenu) p.closeContainer();
				sections += restoreBlocks(level);
				resetEntities(level);
			}
			// Mobs, boats, item frames... back as they were when they first loaded (spawned as their chunks load).
			Checkpoint all = new Checkpoint(0, "", "", 0, Map.of());
			all.entities = firstSeenTag();
			all.gone = Set.of();
			EntityRestore.begin(server, all);
			WorldReset.applyClocks(server, clocks);
			StateRebind.restoreWeather(server, weather);
		} finally {
			restoring = false;
		}
		DIRTY.clear();
		FRESH_AT.clear(); // what survived this reset because it was new goes at the next one
		// These count as changes again, so the next reset fills them back in first.
		Graves.afterReset(server);
		if (clearAtPlayers) for (ServerPlayer p : server.getPlayerList().getPlayers()) clearAround(p);
		// A reset can take a moment; don't let the server rush through the ticks it "missed" meanwhile.
		((dev.recur.mixin.MinecraftServerAccessor) server).recur$setNextTickTimeNanos(net.minecraft.util.Util.getNanos());
		broadcast(server, Component.literal("The world has reset."));
		save(server);
	}

	// normal loops

	/** Changed chunks that couldn't be put back in place during the last restore (no untouched copy of them). */
	private static int missing;

	/** A normal loop starts (or resumes): start noting what changes. Entities loaded right now are remembered as they are. */
	public static void beginTracking(MinecraftServer server) {
		if (active) return; // Timed mode is already tracking.
		DIRTY.clear(); GENERATED.clear(); NEW_CHUNKS.clear(); clearFirstSeen();
		for (ServerLevel level : server.getAllLevels()) for (Entity e : level.getAllEntities()) firstSeen(e);
	}

	/** The normal loop stopped. */
	public static void endTracking() {
		if (active) return;
		DIRTY.clear(); GENERATED.clear(); NEW_CHUNKS.clear(); clearFirstSeen();
	}

	/**
	 * The reset of a normal loop, without unloading anything: the files become the untouched world again, every changed
	 * section of a loaded chunk is put back in place, and every entity goes (LoopManager puts them back as they were when
	 * first seen, see firstSeenEntities). Unchanged chunks stay loaded as they are; unloaded ones load the untouched copy
	 * by themselves later. Returns false if some changed chunk had no untouched copy (then a full reset is needed).
	 */
	public static boolean restoreInPlace(MinecraftServer server) {
		long started = System.nanoTime();
		server.saveEverything(true, true, true);
		LoopOverlay.clearAll();
		StateRebind.rebindAll(server);
		restoring = true;
		missing = 0;
		int sections = 0;
		try {
			for (ServerLevel level : server.getAllLevels()) {
				sections += restoreBlocks(level);
				resetEntities(level);
			}
		} finally {
			restoring = false;
		}
		DIRTY.clear();
		((dev.recur.mixin.MinecraftServerAccessor) server).recur$setNextTickTimeNanos(net.minecraft.util.Util.getNanos());
		return missing == 0;
	}

	/** Every entity as it was the first time it was seen during this loop (by dimension), for EntityRestore. */
	public static CompoundTag firstSeenEntities() { return firstSeenTag(); }

	// blocks

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	/** Puts every changed section of every loaded chunk back to the untouched copy. Returns how many sections. */
	private static int restoreBlocks(ServerLevel level) {
		LongOpenHashSet dirty = DIRTY.get(level);
		if (dirty == null || dirty.isEmpty()) return 0;
		Map<Long, List<SectionPos>> byChunk = new LinkedHashMap<>();
		for (long s : dirty) {
			SectionPos sp = SectionPos.of(s);
			byChunk.computeIfAbsent(ChunkPos.pack(sp.x(), sp.z()), k -> new ArrayList<>()).add(sp);
		}
		int count = 0;
		for (var entry : byChunk.entrySet()) {
			ChunkPos cpos = ChunkPos.unpack(entry.getKey());
			LevelChunk chunk = level.getChunkSource().getChunkNow(cpos.x(), cpos.z());
			if (chunk == null) continue; // not loaded: it'll load the untouched copy by itself
			var fresh = NEW_CHUNKS.get(level);
			if (fresh != null && fresh.contains(cpos.pack())) { count += restoreNewChunk(level, entry.getValue()); continue; }
			CompoundTag tag = untouched(level, cpos);
			if (tag == null) { missing++; continue; }
			SerializableChunkData data;
			try { data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag); }
			catch (Exception e) { Recur.LOGGER.warn("Could not read the untouched copy of chunk {}", cpos, e); continue; }
			Map<Integer, LevelChunkSection> sections = new HashMap<>();
			for (var sd : data.sectionData()) if (sd.chunkSection() != null) sections.put(sd.y(), sd.chunkSection());
			Set<Integer> dirtyY = new HashSet<>();
			for (SectionPos sp : entry.getValue()) {
				if (level.isOutsideBuildHeight(sp.minBlockY())) continue;
				dirtyY.add(sp.y());
				restoreSection(level, sp, sections.get(sp.y()));
				count++;
			}
			for (CompoundTag be : data.blockEntities()) {
				BlockPos pos = BlockEntity.getPosFromTag(cpos, be);
				if (dirtyY.contains(SectionPos.blockToSectionCoord(pos.getY()))) restoreBlockEntity(level, pos, be);
			}
		}
		return count;
	}

	/** A chunk generated during this mode: its changed sections come back from the copies taken before they changed. */
	private static int restoreNewChunk(ServerLevel level, List<SectionPos> dirty) {
		var copies = GENERATED.get(level);
		int count = 0;
		for (SectionPos sp : dirty) {
			if (level.isOutsideBuildHeight(sp.minBlockY())) continue;
			SectionCopy copy = copies != null ? copies.get(sp.asLong()) : null;
			if (copy == null) { missing++; continue; }
			restoreSection(level, sp, copy.states());
			for (CompoundTag be : copy.blockEntities()) {
				BlockPos pos = new BlockPos(be.getIntOr("x", 0), be.getIntOr("y", 0), be.getIntOr("z", 0));
				restoreBlockEntity(level, pos, be);
			}
			count++;
		}
		return count;
	}

	private static void restoreSection(ServerLevel level, SectionPos sp, LevelChunkSection untouched) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			pos.set(sp.minBlockX() + x, sp.minBlockY() + y, sp.minBlockZ() + z);
			BlockState want = untouched != null ? untouched.getBlockState(x, y, z) : air;
			if (level.getBlockState(pos) != want) level.setBlock(pos, want, FLAGS);
		}
	}

	/** Like checkpoints, this loads into the block entity the block already has, so furnaces and similar blocks keep ticking. */
	private static void restoreBlockEntity(ServerLevel level, BlockPos pos, CompoundTag tag) {
		BlockEntity existing = level.getBlockEntity(pos);
		if (existing != null) {
			existing.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
			existing.setChanged();
			level.sendBlockUpdated(pos, existing.getBlockState(), existing.getBlockState(), Block.UPDATE_ALL);
		} else {
			BlockEntity be = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, level.registryAccess());
			if (be != null) level.setBlockEntity(be);
		}
	}

	/** The chunk as it was, from disk (chunks generated during this mode are handled by restoreNewChunk). */
	private static CompoundTag untouched(ServerLevel level, ChunkPos pos) {
		try {
			return ((dev.recur.mixin.ChunkMapAccessor) level.getChunkSource().chunkMap).recur$readChunk(pos).join().orElse(null);
		} catch (Exception e) {
			Recur.LOGGER.warn("Could not read chunk {} from disk", pos, e);
			return null;
		}
	}

	/** After the reset: the two blocks a player is in are cleared if they're solid (they'd suffocate otherwise). */
	private static void clearAround(ServerPlayer p) {
		if (p.isSpectator() || p.isPassenger()) return;
		ServerLevel level = p.level();
		BlockPos feet = p.blockPosition();
		for (BlockPos pos : new BlockPos[] { feet, feet.above() }) {
			BlockState s = level.getBlockState(pos);
			if (!s.isAir() && !s.getCollisionShape(level, pos).isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	// entities

	/** Entities created in the last moments before a reset survive it (a sword dropped right at the end isn't lost). */
	private static final int KEEP_RECENT_TICKS = 20 * 20;
	/** When each entity created during this run was made (server tick). */
	private static final Map<UUID, Integer> FRESH_AT = new HashMap<>();

	/** A brand-new entity (not loaded from disk) appeared. A clone's, or one its replay made, doesn't count. */
	public static void onFresh(Entity e) {
		if (!active || restoring || EntityRestore.isQuiet() || ReplayScope.depth > 0 || e instanceof Player) return;
		if (e.entityTags().contains(com.mt1006.mocap.mocap.playing.Playing.MOCAP_ENTITY_TAG) || e.entityTags().contains(CloneMade.TAG)) return;
		FRESH_AT.put(e.getUUID(), e.level().getServer().getTickCount());
	}

	/**
	 * Removes every entity, such as mobs, items, experience and boats. Players, what they ride, the items of graves and
	 * entities created within the last 20 seconds before the reset are kept.
	 */
	private static void resetEntities(ServerLevel level) {
		int now = level.getServer().getTickCount();
		List<Entity> remove = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e instanceof Player || Graves.isGraveItem(e)) continue;
			Integer at = FRESH_AT.get(e.getUUID());
			if (at != null && now - at <= KEEP_RECENT_TICKS) continue;
			boolean carriesPlayer = false;
			for (Entity p : e.getIndirectPassengers()) if (p instanceof Player) carriesPlayer = true;
			if (carriesPlayer || (e.getRootVehicle() instanceof Player)) continue;
			remove.add(e);
		}
		remove.forEach(Entity::discard);
	}

	private static CompoundTag firstSeenTag() {
		CompoundTag dims = new CompoundTag();
		for (var e : FIRST_SEEN.entrySet()) {
			ListTag list = new ListTag();
			e.getValue().values().forEach(t -> list.add(t.copy()));
			if (!list.isEmpty()) dims.put(e.getKey().dimension().identifier().toString(), list);
		}
		return dims;
	}

	// messaging

	private static String describe(int ticks) {
		int s = ticks / 20;
		if (s % 60 == 0) return (s / 60) + (s == 60 ? " minute" : " minutes");
		return s + " seconds";
	}

	private static void broadcast(MinecraftServer server, Component message) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendOverlayMessage(message);
	}

	// persistence

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("recur").resolve("timed.dat");
	}

	public static void save(MinecraftServer server) {
		CompoundTag root = new CompoundTag();
		root.putBoolean("active", active);
		root.putInt("interval", intervalTicks);
		root.putBoolean("clearAtPlayers", clearAtPlayers);
		root.putBoolean("antiDupe", antiDupe);
		CompoundTag ct = new CompoundTag();
		clocks.forEach(ct::putLong);
		root.put("clocks", ct);
		root.put("weather", weather);
		root.put("graves", Graves.toTag());
		root.put("mined", CloneMade.toTag());
		try {
			Path f = file(server);
			Files.createDirectories(f.getParent());
			NbtIo.writeCompressed(root, f);
		} catch (IOException e) {
			Recur.LOGGER.error("Could not save timed reset state", e);
		}
	}

	/** Server start: read the settings, and carry on if the mode was on (the files on disk are still untouched). */
	public static void load(MinecraftServer server) {
		shutdown();
		ServerSettings.Values d = ServerSettings.defaults();
		intervalTicks = d.timedSeconds() * 20;
		clearAtPlayers = d.clearBlocks();
		antiDupe = d.antiDupe();
		Path f = file(server);
		if (!Files.exists(f)) return;
		try {
			CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
			intervalTicks = Math.max(20 * 10, root.getIntOr("interval", intervalTicks));
			clearAtPlayers = root.getBooleanOr("clearAtPlayers", clearAtPlayers);
			antiDupe = root.getBooleanOr("antiDupe", antiDupe);
			if (!root.getBooleanOr("active", false) || !WorldGuard.allowed()) return;
			Map<String, Long> c = new HashMap<>();
			CompoundTag ct = root.getCompoundOrEmpty("clocks");
			for (String key : ct.keySet()) c.put(key, ct.getLongOr(key, 0));
			clocks = c;
			weather = root.getCompoundOrEmpty("weather");
			Graves.fromTag(server, root.getListOrEmpty("graves"));
			CloneMade.fromTag(root.getCompoundOrEmpty("mined"));
			LoopOverlay.active = true;
			active = true;
			// The spawn chunks loaded before this ran, so their entities are remembered as they are, untouched from disk.
			for (ServerLevel level : server.getAllLevels()) for (Entity e : level.getAllEntities()) firstSeen(e);
			Recur.LOGGER.info("Resumed timed resets every {} seconds", intervalTicks / 20);
		} catch (Exception e) {
			Recur.LOGGER.error("Could not load timed reset state", e);
		}
	}

	// graves

	/**
	 * Dying in this mode keeps your items together in the block you died in, and every reset leaves that one block as air
	 * (so the items aren't pushed out through the restored blocks), until someone picks one of them up or they despawn.
	 */
	public static final class Graves {
		private static final class Grave {
			final ResourceKey<Level> dim;
			final BlockPos pos;
			/** Each item's last known state, to put it back if its chunk wasn't loaded during a reset. */
			final Map<UUID, CompoundTag> items = new LinkedHashMap<>();
			final Map<UUID, Integer> counts = new HashMap<>();
			/** A reset happened while the chunk wasn't loaded: its items are gone from it, and the air gap is missing. */
			boolean needsRestore;
			Grave(ResourceKey<Level> dim, BlockPos pos) { this.dim = dim; this.pos = pos; }
		}

		private static final List<Grave> GRAVES = new ArrayList<>();
		private static final Set<UUID> ITEMS = new HashSet<>();

		private Graves() {}

		static void clear() { GRAVES.clear(); ITEMS.clear(); }

		public static boolean isGraveItem(Entity e) { return e instanceof ItemEntity && ITEMS.contains(e.getUUID()); }

		/** Instead of the usual death drops: everything goes into the block where the player died, without flying about. */
		public static void make(ServerPlayer player) {
			ServerLevel level = player.level();
			BlockPos pos = player.blockPosition();
			Grave grave = new Grave(level.dimension(), pos.immutable());
			var inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.isEmpty()) continue;
				inv.setItem(i, ItemStack.EMPTY);
				ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, stack, 0, 0, 0);
				item.setPickUpDelay(40);
				if (!level.addFreshEntity(item)) continue;
				grave.items.put(item.getUUID(), save(item));
				grave.counts.put(item.getUUID(), stack.getCount());
				ITEMS.add(item.getUUID());
			}
			if (!grave.items.isEmpty()) GRAVES.add(grave);
		}

		private static CompoundTag save(Entity e) {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.level().registryAccess());
			return e.save(out) ? out.buildResult() : null;
		}

		/** Checked every tick. A grave ends as soon as one of its items is picked up (even partly), despawns or is destroyed. */
		static void tick(MinecraftServer server) {
			if (GRAVES.isEmpty() || server.getTickCount() % 5 != 0) return;
			for (var it = GRAVES.iterator(); it.hasNext(); ) {
				Grave g = it.next();
				ServerLevel level = server.getLevel(g.dim);
				if (level == null) { end(g); it.remove(); continue; }
				ChunkPos chunk = ChunkPos.containing(g.pos);
				if (!level.areEntitiesLoaded(chunk.pack())) continue;
				if (g.needsRestore) restore(level, g);
				boolean ended = false;
				for (UUID id : g.items.keySet()) {
					Entity e = level.getEntity(id);
					if (!(e instanceof ItemEntity item) || item.isRemoved() || item.getItem().getCount() < g.counts.getOrDefault(id, 0)) { ended = true; break; }
					CompoundTag tag = save(item);
					if (tag != null) g.items.put(id, tag);
				}
				if (ended) { end(g); it.remove(); }
			}
		}

		private static void end(Grave g) { g.items.keySet().forEach(ITEMS::remove); }

		/** Right after a reset, loaded graves get their air gap. The others get it when their chunk loads. */
		static void afterReset(MinecraftServer server) {
			for (Grave g : GRAVES) {
				ServerLevel level = server.getLevel(g.dim);
				boolean loaded = level != null && level.areEntitiesLoaded(ChunkPos.containing(g.pos).pack()) && level.isLoaded(g.pos);
				if (loaded) makeAir(level, g.pos);
				else g.needsRestore = true;
			}
		}

		static void onChunkLoad(ServerLevel level, ChunkPos pos) {
			for (Grave g : GRAVES) if (g.needsRestore && g.dim == level.dimension() && ChunkPos.containing(g.pos).equals(pos)) makeAir(level, g.pos);
		}

		/** Its chunk loaded again after a reset, so it gets its air gap and the items that were lost from it. */
		private static void restore(ServerLevel level, Grave g) {
			g.needsRestore = false;
			makeAir(level, g.pos);
			for (var entry : g.items.entrySet()) {
				if (level.getEntity(entry.getKey()) != null || entry.getValue() == null) continue;
				Entity e = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), entry.getValue()),
					level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
				if (e != null) level.addFreshEntity(e);
			}
		}

		private static void makeAir(ServerLevel level, BlockPos pos) {
			if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}

		static ListTag toTag() {
			ListTag list = new ListTag();
			for (Grave g : GRAVES) {
				CompoundTag t = new CompoundTag();
				t.putString("dim", g.dim.identifier().toString());
				t.putLong("pos", g.pos.asLong());
				ListTag items = new ListTag();
				for (var e : g.items.entrySet()) {
					if (e.getValue() == null) continue;
					CompoundTag it = e.getValue().copy();
					it.putInt("recur_count", g.counts.getOrDefault(e.getKey(), 0));
					items.add(it);
				}
				t.put("items", items);
				list.add(t);
			}
			return list;
		}

		/** After a server restart the world on disk is untouched, so every grave needs its items and air gap back. */
		static void fromTag(MinecraftServer server, ListTag list) {
			clear();
			for (Tag t : list) {
				if (!(t instanceof CompoundTag c)) continue;
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(c.getStringOr("dim", "minecraft:overworld")));
				Grave g = new Grave(dim, BlockPos.of(c.getLongOr("pos", 0)));
				for (Tag it : c.getListOrEmpty("items")) {
					if (!(it instanceof CompoundTag item)) continue;
					UUID id = item.read("UUID", UUIDUtil.CODEC).orElse(null);
					if (id == null) continue;
					g.counts.put(id, item.getIntOr("recur_count", item.getIntOr(Legacy.old("recur_count"), 0)));
					CompoundTag copy = item.copy();
					copy.remove("recur_count");
					copy.remove(Legacy.old("recur_count"));
					g.items.put(id, copy);
					ITEMS.add(id);
				}
				g.needsRestore = true;
				if (!g.items.isEmpty()) GRAVES.add(g);
			}
		}
	}
}
