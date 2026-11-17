package dev.recur.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** What a player's hit sounds like (its crit, its sweep) belongs to what they hit (see SoundLog.targetGone). */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void recur$start(Entity target, CallbackInfo ci) {
		dev.recur.SoundLog.startAttack(target);
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void recur$end(Entity target, CallbackInfo ci) {
		dev.recur.SoundLog.endAttack();
	}

	/** Only runs when the game really does a sweep (on the ground, not sprinting, a full-strength sword hit). */
	@Inject(method = "doSweepAttack", at = @At("HEAD"))
	private void recur$sweep(Entity target, float damage, net.minecraft.world.damagesource.DamageSource source, float attackStrength, CallbackInfo ci) {
		dev.recur.SoundLog.startSweep((Player) (Object) this);
	}

	@Inject(method = "doSweepAttack", at = @At("RETURN"))
	private void recur$sweepDone(Entity target, float damage, net.minecraft.world.damagesource.DamageSource source, float attackStrength, CallbackInfo ci) {
		dev.recur.SoundLog.endSweep();
	}
}
