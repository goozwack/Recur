package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Controlled;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vehicle a clone is still going to move can be sat in, but not driven: the clone's recording decides where it goes.
 * (Once the clone lets go of it, it's a normal vehicle again.)
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VehicleControlMixin {
	@Shadow public ServerPlayer player;

	@Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
	private void recur$cloneDrives(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
		if (!this.player.level().getServer().isSameThread()) return; // vanilla moves it onto the server thread first
		Entity vehicle = this.player.getRootVehicle();
		if (vehicle != this.player && Controlled.isControlled(vehicle)) {
			this.player.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
			ci.cancel();
		}
	}
}
