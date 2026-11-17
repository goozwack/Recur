package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks "the server is now doing something because this player asked": using an item on a block or in the air,
 * breaking, attacking, interacting with entities, editing signs, clicking in containers, and moving (pressure plates,
 * tripwires, trampled farmland...). Every block that changes
 * meanwhile is recorded for that player's clone (see WorldLog).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerActionMixin {
	@Shadow public ServerPlayer player;

	@Inject(method = {"handleUseItemOn", "handleUseItem", "handlePlayerAction", "handleInteract", "handleAttack",
		"handleSignUpdate", "handleContainerClick", "handleMovePlayer", "handleMoveVehicle", "handleSetBeaconPacket", "handleContainerButtonClick", "handleContainerSlotStateChanged"}, at = @At("HEAD"))
	private void recur$begin(CallbackInfo ci) {
		WorldLog.beginAction(this.player);
	}

	@Inject(method = {"handleUseItemOn", "handleUseItem", "handlePlayerAction", "handleInteract", "handleAttack",
		"handleSignUpdate", "handleContainerClick", "handleMovePlayer", "handleMoveVehicle", "handleSetBeaconPacket", "handleContainerButtonClick", "handleContainerSlotStateChanged"}, at = @At("RETURN"))
	private void recur$end(CallbackInfo ci) {
		// choosing a beacon's effects changes only its block entity: record the beacon
		if (this.player.containerMenu instanceof net.minecraft.world.inventory.BeaconMenu menu)
			((dev.recur.mixin.BeaconMenuAccessor) menu).recur$access().execute((level, pos) -> WorldLog.onBlockEntityChanged(this.player, level, pos));
		// a crafter's slots switched on or off are kept in its block entity too
		else if (this.player.containerMenu instanceof net.minecraft.world.inventory.CrafterMenu menu && !menu.slots.isEmpty()
			&& menu.slots.get(0).container instanceof net.minecraft.world.level.block.entity.CrafterBlockEntity crafter)
			WorldLog.onBlockEntityChanged(this.player, crafter.getLevel(), crafter.getBlockPos());
		WorldLog.endAction(this.player);
	}
}
