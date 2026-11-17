package dev.recur.mixin;

import dev.recur.LoopOverlay;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * While a loop runs, chunk writes go into memory instead of the region files (see LoopOverlay). They're kept compressed:
 * a whole explored area of uncompressed chunk data would fill the memory and make the game stutter on garbage
 * collection. Reads and writes run on the storage's own IO thread, so the compression costs the game nothing.
 * With C2ME, its storage thread skips these methods and uses the same memory itself (see C2meStorageMixin).
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements LoopOverlay.Store {
	@Unique private final Map<Long, byte[]> recur$overlay = new ConcurrentHashMap<>();
	@Unique private volatile BiConsumer<ChunkPos, CompoundTag> recur$writer;

	@Inject(method = "write", at = @At("HEAD"), cancellable = true)
	private void recur$write(ChunkPos pos, CompoundTag value, CallbackInfo ci) throws IOException {
		if (!LoopOverlay.active) return;
		recur$put(ChunkPos.pack(pos.x(), pos.z()), LoopOverlay.pack(value));
		ci.cancel();
	}

	@Inject(method = "read", at = @At("HEAD"), cancellable = true)
	private void recur$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
		byte[] chunk = recur$get(ChunkPos.pack(pos.x(), pos.z()));
		if (chunk != null) cir.setReturnValue(LoopOverlay.unpack(chunk));
	}

	@Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
	private void recur$scan(ChunkPos pos, StreamTagVisitor scanner, CallbackInfo ci) throws IOException {
		byte[] chunk = recur$get(ChunkPos.pack(pos.x(), pos.z()));
		if (chunk == null) return;
		if (chunk != LoopOverlay.TOMBSTONE) {
			try (DataInputStream in = LoopOverlay.open(chunk)) {
				NbtIo.parse(in, scanner, NbtAccounter.unlimitedHeap());
			}
		}
		ci.cancel();
	}

	@Override
	public byte[] recur$get(long pos) {
		return recur$overlay.isEmpty() ? null : recur$overlay.get(pos);
	}

	@Override
	public void recur$put(long pos, byte[] chunk) {
		recur$overlay.put(pos, chunk);
		LoopOverlay.register(this);
	}

	@Override public void recur$setWriter(BiConsumer<ChunkPos, CompoundTag> writer) { recur$writer = writer; }

	@Override public void recur$clear() { recur$overlay.clear(); }

	/** Hands the loop's chunks to the IO thread that owns this storage (writing here would race with it). */
	@Override
	public void recur$flushToDisk() {
		RegionFileStorage self = (RegionFileStorage) (Object) this;
		BiConsumer<ChunkPos, CompoundTag> writer = recur$writer;
		for (Map.Entry<Long, byte[]> e : recur$overlay.entrySet()) {
			long packed = e.getKey();
			ChunkPos pos = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
			try {
				CompoundTag tag = LoopOverlay.unpack(e.getValue());
				if (writer != null) writer.accept(pos, tag);
				else self.write(pos, tag);
			} catch (IOException ex) {
				dev.recur.Recur.LOGGER.error("Failed to flush overlay chunk {}", pos, ex);
			}
		}
		recur$overlay.clear();
	}
}
