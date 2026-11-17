package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Clones don't keep mobs from despawning (otherwise mobs pile up around every clone). */
@Mixin(Mob.class)
public abstract class CloneDespawnMixin {
	@Redirect(method = "checkDespawn", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"),
		require = 0)
	private Player recur$ignoreClones(Level level, Entity source, double maxDist) {
		return level.getNearestPlayer(source.getX(), source.getY(), source.getZ(), maxDist,
			e -> !(e instanceof FakePlayer) && EntitySelector.NO_SPECTATORS.test(e));
	}
}
