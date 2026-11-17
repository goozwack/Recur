package dev.recur.mixin;

import dev.recur.LoopOverlay;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Optional;

@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin implements LoopOverlay.DataStore {
	@Shadow @org.spongepowered.asm.mixin.Final
	private Map<SavedDataType<?>, Optional<SavedData>> cache;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void recur$register(CallbackInfo ci) {
		LoopOverlay.registerData(this);
	}

	/** While a loop is active nothing may reach the disk, so the disk stays the pristine snapshot. */
	@Inject(method = "tryWrite", at = @At("HEAD"), cancellable = true)
	private void recur$blockWrite(SavedDataType<?> type, net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) {
		if (LoopOverlay.active) ci.cancel();
	}

	@Override
	public void recur$resetCache() {
		cache.clear();
	}

	@Override
	public void recur$markAllDirty() {
		cache.values().forEach(o -> o.ifPresent(SavedData::setDirty));
	}
}
