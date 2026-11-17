package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager's job changed (see WorldLog.onVillagerJobChanged). */
@Mixin(Villager.class)
public abstract class VillagerJobMixin {
	@Unique private boolean recur$jobChanging;

	@Inject(method = "setVillagerData", at = @At("HEAD"))
	private void recur$before(VillagerData data, CallbackInfo ci) {
		recur$jobChanging = !((Villager) (Object) this).getVillagerData().profession().equals(data.profession());
	}

	@Inject(method = "setVillagerData", at = @At("RETURN"))
	private void recur$after(VillagerData data, CallbackInfo ci) {
		if (recur$jobChanging) WorldLog.onVillagerJobChanged((Villager) (Object) this);
		recur$jobChanging = false;
	}
}
