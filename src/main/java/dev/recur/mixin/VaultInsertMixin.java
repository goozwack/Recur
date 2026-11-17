package dev.recur.mixin;

import dev.recur.TrialVaults;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server")
public abstract class VaultInsertMixin {
	@Inject(method = "tryInsertKey", at = @At("HEAD"))
	private static void recur$insert(ServerLevel level, BlockPos pos, BlockState state, VaultConfig config, VaultServerData server,
									 VaultSharedData shared, Player player, ItemStack stack, CallbackInfo ci) {
		TrialVaults.onInsert(level, pos, player);
	}
}
