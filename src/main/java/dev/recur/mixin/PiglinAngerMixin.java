package dev.recur.mixin;

import dev.recur.MobTies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A piglin got angry at a player (they opened a chest or mined gold nearby, or hit another piglin). */
@Mixin(PiglinAi.class)
public abstract class PiglinAngerMixin {
	@Inject(method = "setAngerTarget", at = @At("HEAD"))
	private static void recur$anger(ServerLevel level, AbstractPiglin piglin, LivingEntity target, CallbackInfo ci) {
		MobTies.onTargetSet(piglin, target);
	}
}
