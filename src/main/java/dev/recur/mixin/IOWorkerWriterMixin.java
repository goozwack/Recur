package dev.recur.mixin;

import dev.recur.LoopOverlay;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When a loop stops and its chunks are kept, they're written out through this worker, on its own thread. */
@Mixin(IOWorker.class)
public abstract class IOWorkerWriterMixin {
	@Shadow @Final private RegionFileStorage storage;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void recur$init(CallbackInfo ci) {
		IOWorker self = (IOWorker) (Object) this;
		// C2ME's replacement worker drops this storage and brings its own (see C2meStorageMixin).
		if (storage != null) ((LoopOverlay.Store) (Object) storage).recur$setWriter(self::store);
	}
}
