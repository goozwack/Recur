package dev.recur.mixin;

import dev.recur.ReplayScope;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** Knows when an item comes from a block dropping its loot (every block drop goes through here). */
@Mixin(Block.class)
public abstract class BlockDropMixin {
	@Inject(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
	private static void recur$start(Level level, Supplier<ItemEntity> supplier, ItemStack stack, CallbackInfo ci) {
		ReplayScope.blockDrop++;
	}

	@Inject(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
	private static void recur$end(Level level, Supplier<ItemEntity> supplier, ItemStack stack, CallbackInfo ci) {
		if (ReplayScope.blockDrop > 0) ReplayScope.blockDrop--;
	}
}
