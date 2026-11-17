package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player's raid omen set off a raid: their clone sets it off again (see WorldLog, kind 12). */
@Mixin(Raids.class)
public abstract class RaidStartMixin {
	@Unique private static int recur$omen = -1;

	@Inject(method = "createOrExtendRaid", at = @At("HEAD"))
	private void recur$omen(ServerPlayer player, BlockPos pos, CallbackInfoReturnable<Raid> cir) {
		MobEffectInstance omen = player.getEffect(MobEffects.RAID_OMEN);
		recur$omen = omen != null ? omen.getAmplifier() : -1;
	}

	@Inject(method = "createOrExtendRaid", at = @At("RETURN"))
	private void recur$started(ServerPlayer player, BlockPos pos, CallbackInfoReturnable<Raid> cir) {
		if (cir.getReturnValue() != null && recur$omen >= 0) WorldLog.onRaidStarted(player, pos, recur$omen);
		recur$omen = -1;
	}
}
