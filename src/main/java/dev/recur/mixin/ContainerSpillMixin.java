package dev.recur.mixin;

import dev.recur.Reservations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Timed resets: breaking a container (or a chest minecart) doesn't drop the items a clone takes out of it later. */
@Mixin(Containers.class)
public abstract class ContainerSpillMixin {
	@Inject(method = "dropContents(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/Container;)V", at = @At("HEAD"))
	private static void recur$block(Level level, BlockPos pos, Container container, CallbackInfo ci) {
		Reservations.beforeSpill(container);
	}

	@Inject(method = "dropContents(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/Container;)V", at = @At("HEAD"))
	private static void recur$entity(Level level, Entity entity, Container container, CallbackInfo ci) {
		Reservations.beforeSpill(container);
	}
}
