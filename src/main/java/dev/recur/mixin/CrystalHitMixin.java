package dev.recur.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A crystal a player shot (not just punched) is in their recording too, so their clone blows it up again. */
@Mixin(EndCrystal.class)
public class CrystalHitMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void recur$hit(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		if (source.getEntity() instanceof ServerPlayer p && source.getDirectEntity() != p && !(p instanceof com.mt1006.mocap.utils.FakePlayer))
			dev.recur.WorldLog.onEntityInteract(p, (EndCrystal) (Object) this);
	}
}
