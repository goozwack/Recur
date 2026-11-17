package dev.recur;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * While a loop is active, every chunk/entity/POI write and every saved-data write (maps, scoreboard, raids...) is
 * redirected away from the disk. The files therefore stay as the pristine snapshot, and a reset is just
 * "drop the overlay and unload everything".
 */
public final class LoopOverlay {
	/**
	 * Implemented by the RegionFileStorage mixin. Each chunk is kept like a region file keeps it: a byte naming the
	 * compression, then the compressed NBT. That's the form C2ME already produces, so its chunks go in as they are.
	 */
	public interface Store {
		void recur$clear();
		void recur$flushToDisk();
		/** The chunk as written during the loop, TOMBSTONE if it was deleted, or null if it wasn't written. */
		byte[] recur$get(long pos);
		void recur$put(long pos, byte[] chunk);
		/** What writes the loop's chunks out when it stops: the thread that owns this storage. */
		void recur$setWriter(BiConsumer<ChunkPos, CompoundTag> writer);
	}

	/** A chunk deleted during the loop. */
	public static final byte[] TOMBSTONE = new byte[0];

	public static byte[] pack(CompoundTag tag) throws IOException {
		if (tag == null) return TOMBSTONE;
		RegionFileVersion version = RegionFileVersion.getSelected();
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		out.write(version.getId());
		try (DataOutputStream data = new DataOutputStream(version.wrap(out))) {
			NbtIo.write(tag, data);
		}
		return out.toByteArray();
	}

	/** Reading a chunk kept by pack (not a TOMBSTONE). */
	public static DataInputStream open(byte[] chunk) throws IOException {
		RegionFileVersion version = RegionFileVersion.fromId(chunk[0]);
		if (version == null) throw new IOException("Unknown chunk compression " + chunk[0]);
		return new DataInputStream(version.wrap(new ByteArrayInputStream(chunk, 1, chunk.length - 1)));
	}

	public static CompoundTag unpack(byte[] chunk) throws IOException {
		if (chunk == TOMBSTONE) return null;
		try (DataInputStream in = open(chunk)) {
			return NbtIo.read(in);
		}
	}

	/** Implemented by the SavedDataStorage mixin. */
	public interface DataStore {
		/** Forget cached saved data so the next access reloads the pristine files. */
		void recur$resetCache();
		/** Mark every cached saved data dirty so the next save writes the loop's state out. */
		void recur$markAllDirty();
	}

	public static volatile boolean active = false;
	private static final Set<Store> STORES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static final Set<DataStore> DATA_STORES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private LoopOverlay() {}

	public static void register(Store store) { STORES.add(store); }
	public static void registerData(DataStore store) { DATA_STORES.add(store); }

	private static <T> List<T> copy(Set<T> set) {
		synchronized (set) { return new ArrayList<>(set); }
	}

	/** Forget everything written during the loop (the world goes back to the pristine disk state). */
	public static void clearAll() {
		copy(STORES).forEach(Store::recur$clear);
		copy(DATA_STORES).forEach(DataStore::recur$resetCache);
	}

	/** Forget everything, including the storages of the world that just closed, so nothing leaks into another world. */
	public static void dropAll() {
		active = false;
		copy(STORES).forEach(Store::recur$clear);
		synchronized (STORES) { STORES.clear(); }
		synchronized (DATA_STORES) { DATA_STORES.clear(); }
	}

	/** Persist the loop's changes (used when the loop is stopped and the world should be kept as it is now). */
	public static void flushAll() {
		active = false;
		copy(STORES).forEach(Store::recur$flushToDisk);
		copy(DATA_STORES).forEach(DataStore::recur$markAllDirty);
	}
}
