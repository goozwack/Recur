package dev.recur;

import com.mt1006.mocap.mocap.playing.Controlled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puts every entity back the way it was when a checkpoint was made: mobs, animals, items, boats, minecarts, armor
 * stands... where they were, with their health, inventories and everything else.
 *
 * After a reset the world's entities come back from the untouched files (as they were at the loop start). So when a run
 * starts at a checkpoint, each saved entity replaces its loop-start copy, and entities that had died or been removed by
 * then are removed again. Chunks that aren't loaded yet are fixed up when they load.
 */
public final class EntityRestore {
	private record Pending(ServerLevel level, long chunk, CompoundTag tag) {}

	/** Entities destroyed so far on this run's timeline (inherited from the checkpoint it started at). */
	private static final Set<UUID> GONE = new HashSet<>();
	/** Saved entities still waiting for their chunk to load. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final List<Entity> TO_DISCARD = new ArrayList<>();
	private static boolean active;
	/** True while we add/remove entities ourselves (so it isn't mistaken for the world's doing). */
	private static boolean quiet;
	/** Above 0 while ServerLevel.addFreshEntity runs, which means the entity is new and was not loaded from disk. */
	public static int addingFresh;

	private EntityRestore() {}

	public static boolean isQuiet() { return quiet; }

	public static void clear() {
		GONE.clear(); PENDING.clear(); TO_DISCARD.clear();
		active = false; quiet = false; addingFresh = 0;
	}

	/** Every loaded entity (players and clones aside), in each dimension. Fills `captured` with their UUIDs. */
	public static CompoundTag capture(MinecraftServer server, Set<UUID> captured) {
		CompoundTag dims = new CompoundTag();
		for (ServerLevel level : server.getAllLevels()) {
			ListTag list = new ListTag();
			for (Entity e : level.getAllEntities()) {
				if (e instanceof Player || e.isPassenger() || e.isRemoved()) continue; // passengers are saved with their vehicle
				CompoundTag tag = Controlled.natural(e, () -> {
					TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
					return e.save(out) ? out.buildResult() : null;
				});
				if (tag == null) continue;
				list.add(tag);
				e.getSelfAndPassengers().forEach(p -> captured.add(p.getUUID()));
			}
			if (!list.isEmpty()) dims.put(level.dimension().identifier().toString(), list);
		}
		return dims;
	}

	/** The entities destroyed so far this run, minus ones that exist (again) now. */
	public static Set<UUID> goneExcept(Set<UUID> alive) {
		Set<UUID> out = new HashSet<>(GONE);
		out.removeAll(alive);
		return out;
	}

	/**
	 * After a reset in place every entity was removed, so they all come back from here: as they were when first seen
	 * (`base`, the loop start), with the checkpoint's own entities (and what had died by then) on top.
	 */
	public static Checkpoint withBase(CompoundTag base, Checkpoint target) {
		Checkpoint out = new Checkpoint(0, "", "", 0, Map.of());
		Set<UUID> gone = target != null && target.entities != null ? target.gone : Set.of();
		CompoundTag dims = new CompoundTag();
		Set<String> keys = new HashSet<>(base.keySet());
		if (target != null && target.entities != null) keys.addAll(target.entities.keySet());
		for (String dim : keys) {
			Map<UUID, Tag> byId = new java.util.LinkedHashMap<>();
			for (Tag t : base.getListOrEmpty(dim))
				if (t instanceof CompoundTag c) c.read("UUID", UUIDUtil.CODEC).ifPresent(id -> { if (!gone.contains(id)) byId.put(id, c); });
			if (target != null && target.entities != null)
				for (Tag t : target.entities.getListOrEmpty(dim))
					if (t instanceof CompoundTag c) c.read("UUID", UUIDUtil.CODEC).ifPresent(id -> byId.put(id, c));
			ListTag list = new ListTag();
			byId.values().forEach(t -> list.add(t.copy()));
			if (!list.isEmpty()) dims.put(dim, list);
		}
		out.entities = dims;
		out.gone = gone;
		return out;
	}

	/** A run starts (the world was just reset): put the checkpoint's entities back. null = the loop start. */
	public static void begin(MinecraftServer server, Checkpoint target) {
		clear();
		active = true;
		if (target == null || target.entities == null) return;
		GONE.addAll(target.gone);
		for (String dim : target.entities.keySet()) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
			if (level == null) continue;
			for (Tag t : target.entities.getListOrEmpty(dim)) {
				if (!(t instanceof CompoundTag tag)) continue;
				UUID uuid = tag.read("UUID", UUIDUtil.CODEC).orElse(null);
				if (uuid == null) continue;
				var pos = tag.getListOrEmpty("Pos");
				int cx = Mth.floor(pos.getDoubleOr(0, 0)) >> 4, cz = Mth.floor(pos.getDoubleOr(2, 0)) >> 4;
				PENDING.put(uuid, new Pending(level, ChunkPos.pack(cx, cz), tag));
			}
		}
		// What's loaded already (around the players) and shouldn't be there, or is there as its loop-start copy.
		quiet = true;
		try {
			for (ServerLevel level : server.getAllLevels()) {
				List<Entity> remove = new ArrayList<>();
				for (Entity e : level.getAllEntities()) {
					if (e instanceof Player) continue;
					if (GONE.contains(e.getUUID()) || PENDING.containsKey(e.getUUID())) remove.add(e);
				}
				remove.forEach(Entity::discard);
			}
		} finally { quiet = false; }
		spawnReady();
	}

	/** It was destroyed this run: it isn't put back (or kept). */
	/** Is an entity of this type still waiting to be put back? */
	public static boolean pending(String type) {
		for (Pending p : PENDING.values()) if (type.equals(p.tag().getStringOr("id", ""))) return true;
		return false;
	}

	/** Is everything that's waiting to be put back in a chunk that isn't loaded (and comes back with it by itself)? */
	public static boolean settled() {
		for (Pending p : PENDING.values())
			if (p.level().getChunkSource().hasChunk(ChunkPos.getX(p.chunk()), ChunkPos.getZ(p.chunk()))) return false;
		return true;
	}

	public static void forget(UUID id) { PENDING.remove(id); GONE.add(id); }

	/** An entity appeared in a level (loaded from disk, or brand new). */
	public static void onLoad(Entity e) {
		if (!active || quiet || e instanceof Player) return;
		UUID id = e.getUUID();
		if (addingFresh > 0) { PENDING.remove(id); return; } // a new one (e.g. a clone's): it wins over the saved copy
		if (GONE.contains(id) || PENDING.containsKey(id)) TO_DISCARD.add(e); // its loop-start copy
	}

	/** An entity left a level: remember the ones that were destroyed (killed, picked up, broken...). */
	public static void onUnload(Entity e) {
		if (!active || quiet || e instanceof Player) return;
		Entity.RemovalReason reason = e.getRemovalReason();
		if (reason != null && reason.shouldDestroy()) GONE.add(e.getUUID());
	}

	public static void tick(MinecraftServer server) {
		addingFresh = 0;
		if (!active) return;
		if (!TO_DISCARD.isEmpty()) {
			quiet = true;
			try {
				for (Entity e : TO_DISCARD) if (!e.isRemoved() && !Controlled.isControlled(e) && !EntityOwners.isOwned(e)) e.discard();
			} finally { quiet = false; }
			TO_DISCARD.clear();
		}
		if (!PENDING.isEmpty() && server.getTickCount() % 10 == 0) spawnReady();
	}

	/** Spawns the saved entities whose chunk is loaded. */
	private static void spawnReady() {
		if (PENDING.isEmpty()) return;
		quiet = true;
		try {
			for (var it = PENDING.entrySet().iterator(); it.hasNext(); ) {
				var entry = it.next();
				Pending p = entry.getValue();
				if (!p.level.areEntitiesLoaded(p.chunk)) continue;
				Entity existing = p.level.getEntity(entry.getKey());
				if (existing != null) {
					if (Controlled.isControlled(existing) || EntityOwners.isOwned(existing)) { it.remove(); continue; }
					existing.discard();
				}
				Entity e = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, p.level.registryAccess(), p.tag),
					p.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
				if (e == null) { it.remove(); continue; }
				e.getPassengers().forEach(passenger -> {
					Entity old = p.level.getEntity(passenger.getUUID());
					if (old != null && !(old instanceof Player)) old.discard();
				});
				// Fails while an unloaded copy still holds the UUID: try again later.
				if (p.level.tryAddFreshEntityWithPassengers(e)) it.remove();
			}
		} finally { quiet = false; }
	}
}
