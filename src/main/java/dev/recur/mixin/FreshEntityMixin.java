package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Playing;
import dev.recur.EntityOwners;
import dev.recur.EntityRestore;
import dev.recur.LoopOverlay;
import dev.recur.ReplayScope;
import com.mt1006.mocap.mocap.recording.Recording;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps track of entities created during the loop, such as drops and placed boats, and who made them. */
@Mixin(ServerLevel.class)
public abstract class FreshEntityMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void recur$beforeAdd(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		// This item is a side effect of a clone's replay, and the clone's recording already has it (see ReplayScope).
		// (Experience too: a mob a clone kills drops the experience its recording has, which flies to the clone.)
		if (ReplayScope.depth > 0 && LoopOverlay.active && (entity instanceof ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb)
			&& !entity.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) {
			cir.setReturnValue(false);
			return;
		}
		// A clone's projectile belongs to the clone (a replayed ender pearl must never teleport the real player).
		if (entity instanceof net.minecraft.world.entity.projectile.Projectile p && entity.entityTags().contains(Playing.MOCAP_ENTITY_TAG)
			&& com.mt1006.mocap.mocap.playing.PlayedScene.currentClone != null) p.setOwner(com.mt1006.mocap.mocap.playing.PlayedScene.currentClone);
		dev.recur.CloneMade.markIfClone(entity);
		EntityRestore.addingFresh++;
	}

	@Inject(method = "addFreshEntity", at = @At("RETURN"))
	private void recur$onAdd(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (EntityRestore.addingFresh > 0) EntityRestore.addingFresh--;
		if (!LoopOverlay.active || !cir.getReturnValueZ()) return;
		dev.recur.MobDrops.onFresh(entity);
		dev.recur.CloneMade.onFresh(entity);
		dev.recur.TimedReset.onFresh(entity);
		dev.recur.WorldLog.onEntitySpawned(entity);
		Recording.FRESH_ENTITIES.add(entity.getUUID());
		if (!EntityRestore.isQuiet()) {
			dev.recur.MobTies.onShot(entity); // before onFresh, so the shot is in the same recording as its shooter
			EntityOwners.onFresh(entity);
		}
	}
}
