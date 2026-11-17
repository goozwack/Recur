package dev.recur.mixin;

import dev.recur.LoopOverlay;
import dev.recur.Recur;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongFunction;

/**
 * C2ME's storage thread reads and writes the region files itself, skipping RegionFileStorage.read and write, so the
 * loop's chunks would go straight to disk. This puts them in the same memory instead (see RegionFileStorageMixin).
 * C2ME's own way is kept: chunks are compressed and read on its background threads, not its storage thread, and
 * C2ME's cache still answers reads of chunks that are on their way in.
 * Nothing here applies when C2ME isn't installed, or if a C2ME update renames these methods.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread", remap = false)
public abstract class C2meStorageMixin {
	@Shadow @Final private RegionFileStorage storage;
	@Shadow @Final private Long2ReferenceLinkedOpenHashMap<CompletionStage<?>> cache;
	@Shadow @Final private LongFunction<Executor> backgroundExecutorSupplier;
	@Shadow @Final private Executor executor;
	@Shadow @Final private ObjectArraySet<CompletableFuture<Void>> writeFutures;

	@Shadow public abstract CompletableFuture<Void> setChunkData(long pos, CompoundTag tag);

	/** C2ME's Either (left: the chunk's NBT, right: its NBT already serialised), read by reflection. */
	@Unique private static volatile Method recur$left, recur$right;

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void recur$init(CallbackInfo ci) {
		((LoopOverlay.Store) (Object) storage).recur$setWriter((pos, tag) -> setChunkData(ChunkPos.pack(pos.x(), pos.z()), tag));
	}

	@Inject(method = "writeChunk", at = @At("HEAD"), cancellable = true, require = 0)
	private void recur$write(long pos, CompletionStage<?> data, CallbackInfo ci) {
		if (!LoopOverlay.active) return;
		ci.cancel();
		LoopOverlay.Store store = (LoopOverlay.Store) (Object) storage;
		CompletableFuture<Void> done = data.toCompletableFuture()
			.thenApplyAsync(C2meStorageMixin::recur$pack, backgroundExecutorSupplier.apply(pos))
			.thenAcceptAsync(chunk -> {
				store.recur$put(pos, chunk);
				cache.remove(pos, data); // reads now find it in the overlay
			}, executor)
			.handle((v, t) -> {
				if (t != null) Recur.LOGGER.error("Failed to keep chunk {} in memory", ChunkPos.unpack(pos), t);
				return null;
			});
		writeFutures.add(done);
	}

	@Inject(method = "scheduleChunkRead", at = @At("HEAD"), cancellable = true, require = 0)
	private void recur$read(long pos, CompletableFuture<CompoundTag> future, StreamTagVisitor scanner, CallbackInfo ci) {
		byte[] chunk = ((LoopOverlay.Store) (Object) storage).recur$get(pos);
		if (chunk == null) return;
		ci.cancel();
		if (chunk == LoopOverlay.TOMBSTONE) {
			future.complete(null);
			return;
		}
		backgroundExecutorSupplier.apply(pos).execute(() -> {
			try (DataInputStream in = LoopOverlay.open(chunk)) {
				// With a scanner the chunk is only scanned, and the future gets null (like C2ME does).
				if (scanner != null) {
					NbtIo.parse(in, scanner, NbtAccounter.unlimitedHeap());
					future.complete(null);
				} else {
					future.complete(NbtIo.read(in));
				}
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
	}

	/** The chunk in the overlay's form (see LoopOverlay.Store); null means C2ME is deleting it. */
	@Unique
	private static byte[] recur$pack(Object either) {
		try {
			if (either == null) return LoopOverlay.TOMBSTONE;
			if (recur$left == null) {
				Class<?> type = Class.forName("com.ibm.asyncutil.util.Either", false, either.getClass().getClassLoader());
				recur$right = type.getMethod("right");
				recur$left = type.getMethod("left");
			}
			Optional<?> left = (Optional<?>) recur$left.invoke(either);
			if (left.isPresent()) return LoopOverlay.pack((CompoundTag) left.get());
			byte[] raw = (byte[]) ((Optional<?>) recur$right.invoke(either)).orElseThrow();
			RegionFileVersion version = RegionFileVersion.getSelected();
			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 64);
			out.write(version.getId());
			try (DataOutputStream o = new DataOutputStream(version.wrap(out))) {
				o.write(raw);
			}
			return out.toByteArray();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
