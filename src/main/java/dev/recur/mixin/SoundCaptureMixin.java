package dev.recur.mixin;

import dev.recur.SoundLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every sound and level event (particles with a sound, like lava fizzing) the server sends goes through here. A
 * recording player's ones are saved for their clone, and the ones a clone's replay would make by itself are dropped,
 * because the recorded ones play instead (see SoundLog).
 */
@Mixin(ServerLevel.class)
public abstract class SoundCaptureMixin {
	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void recur$sound(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume,
							 float pitch, long seed, CallbackInfo ci) {
		if (!SoundLog.onSound((ServerLevel) (Object) this, except, null, x, y, z, sound, source, volume, pitch, seed)) ci.cancel();
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void recur$entitySound(Entity except, Entity sourceEntity, Holder<SoundEvent> sound, SoundSource source, float volume,
								   float pitch, long seed, CallbackInfo ci) {
		if (!SoundLog.onSound((ServerLevel) (Object) this, except, sourceEntity, sourceEntity.getX(), sourceEntity.getY(), sourceEntity.getZ(),
			sound, source, volume, pitch, seed)) ci.cancel();
	}

	@Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
	private void recur$levelEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
		if (!SoundLog.onLevelEvent((ServerLevel) (Object) this, source, type, pos, data)) ci.cancel();
	}
}
