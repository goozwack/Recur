package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Clones replaying a death don't post "X was killed" in chat. */
@Mixin(ServerPlayer.class)
public abstract class CloneDeathMessageMixin {
	@ModifyVariable(method = "die", at = @At("STORE"), ordinal = 0)
	private boolean recur$noCloneDeathMessage(boolean showDeathMessage) {
		return showDeathMessage && !((Object) this instanceof FakePlayer);
	}
}
