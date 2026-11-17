package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records sign text a player writes. */
@Mixin(SignBlockEntity.class)
public abstract class SignEditMixin {
	@Inject(method = "updateSignText", at = @At("TAIL"))
	private void recur$onEdit(Player player, net.minecraft.world.level.block.entity.SignTextSlot slot, java.util.List<net.minecraft.server.network.FilteredText> lines, CallbackInfo ci) {
		WorldLog.onSignEdited(player, (SignBlockEntity) (Object) this);
	}
}
