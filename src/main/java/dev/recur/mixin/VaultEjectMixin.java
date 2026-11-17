package dev.recur.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.recur.MobDrops;
import dev.recur.TrialVaults;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.vault.VaultState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** What a vault gives a clone comes from the recording; what it gives a player remembers the vault (see TrialVaults). */
@Mixin(VaultState.class)
public abstract class VaultEjectMixin {
	@WrapOperation(method = "ejectResultItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/dispenser/DefaultDispenseItemBehavior;spawnItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;Lnet/minecraft/core/Position;)V"))
	private static void recur$eject(Level level, ItemStack stack, int speed, Direction dir, Position at, Operation<Void> original) {
		BlockPos vault = BlockPos.containing(at).below(); // it comes out just above the vault
		if (TrialVaults.blockEject(level, vault)) return;
		MobDrops.startReward("v|" + TrialVaults.key(level, vault));
		try { original.call(level, stack, speed, dir, at); }
		finally { MobDrops.endReward(); }
	}
}
