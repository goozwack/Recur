package dev.recur.mixin;

import dev.recur.SoundLog;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Which entity is being ticked (what its sounds and particles come from), and the particles sent (see SoundLog). */
@Mixin(ServerLevel.class)
public abstract class EffectSourceMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"))
	private void recur$ticking(Entity entity, CallbackInfo ci) {
		SoundLog.ticking = entity;
	}

	@Inject(method = "tickNonPassenger", at = @At("RETURN"))
	private void recur$ticked(Entity entity, CallbackInfo ci) {
		SoundLog.ticking = null;
	}

	@Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDDDDLnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket$RandomizationType;)I",
		at = @At("HEAD"))
	private void recur$particles(ParticleOptions options, boolean overrideLimiter, boolean alwaysShow, double x, double y, double z, int count,
								 double dx, double dy, double dz, double speed, double a, double b,
								 ClientboundLevelParticlesPacket.RandomizationType randomization, CallbackInfoReturnable<Integer> cir) {
		SoundLog.onParticles((ServerLevel) (Object) this, options, overrideLimiter, alwaysShow, x, y, z, count, dx, dy, dz, speed, a, b, randomization);
	}
}
