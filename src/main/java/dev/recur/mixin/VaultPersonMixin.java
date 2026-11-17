package dev.recur.mixin;

import dev.recur.TrialVaults;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** A vault knows a clone as its player, so it opens once for both (see TrialVaults). */
@Mixin(VaultServerData.class)
public abstract class VaultPersonMixin {
	@Inject(method = "hasRewardedPlayer", at = @At("HEAD"), cancellable = true)
	private void recur$rewarded(Player player, CallbackInfoReturnable<Boolean> cir) {
		UUID person = TrialVaults.person(player);
		if (person != null) cir.setReturnValue(((VaultServerDataAccessor) this).recur$rewardedPlayers().contains(person));
	}

	@Inject(method = "addToRewardedPlayers", at = @At("HEAD"), cancellable = true)
	private void recur$reward(Player player, CallbackInfo ci) {
		UUID person = TrialVaults.person(player);
		if (person == null) return;
		((VaultServerDataAccessor) this).recur$rewardedPlayers().add(person);
		((VaultServerDataAccessor) this).recur$setDirty(true);
		ci.cancel();
	}
}
