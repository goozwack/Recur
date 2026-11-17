package dev.recur.mixin;

import dev.recur.EntityRestore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * A dragon a clone plays has its AI off, so the fight never "sees" it and would make a second one; and after a reset
 * the dragon may still be on its way back (see EntityRestore). Either way there's no new dragon.
 */
@Mixin(EnderDragonFight.class)
public abstract class DragonFightMixin {
	@Shadow private ServerLevel level;
	@Shadow private UUID dragonUUID;

	@Inject(method = "findOrCreateDragon", at = @At("HEAD"), cancellable = true)
	private void recur$keepDragon(CallbackInfo ci) {
		if (!dev.recur.LoopManager.isActive() && !dev.recur.TimedReset.isActive()) return;
		var dragons = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class), d -> !d.isRemoved());
		if (!dragons.isEmpty()) { dragonUUID = dragons.get(0).getUUID(); ci.cancel(); }
		// right after a reset the saved dragon is still loading back in
		else if (EntityRestore.pending("minecraft:ender_dragon") || dev.recur.BossReplay.justStarted()) ci.cancel();
	}
}
