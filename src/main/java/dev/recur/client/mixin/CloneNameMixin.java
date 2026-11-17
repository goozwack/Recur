package dev.recur.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A clone's player name is "Name_N" so commands can use it, but above its head it shows "Name #N", the name the server
 * puts in the player list for it.
 */
@Mixin(Player.class)
public abstract class CloneNameMixin {
	@Inject(method = "getName", at = @At("HEAD"), cancellable = true)
	private void recur$worldName(CallbackInfoReturnable<Component> cir) {
		Player self = (Player) (Object) this;
		if (self.level() == null || !self.level().isClientSide()) return;
		var connection = Minecraft.getInstance().getConnection();
		if (connection == null) return;
		PlayerInfo info = connection.getPlayerInfo(self.getUUID());
		if (info == null || info.getTabListDisplayName() == null) return;
		String shown = info.getTabListDisplayName().getString();
		String name = self.getGameProfile().name();
		int hash = shown.lastIndexOf(" #"), under = name.lastIndexOf('_');
		if (hash < 0 || under < 0 || !shown.substring(hash + 2).equals(name.substring(under + 1))
			|| !shown.startsWith(name.substring(0, under))) return;
		cir.setReturnValue(Component.literal(shown));
	}
}
