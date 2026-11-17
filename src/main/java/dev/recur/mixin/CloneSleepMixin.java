package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Clones don't count for sleeping: otherwise the night could never be skipped while any clone is awake. */
@Mixin(SleepStatus.class)
public abstract class CloneSleepMixin {
	@ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
	private List<ServerPlayer> recur$noClones(List<ServerPlayer> players) { return withoutClones(players); }

	@ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
	private List<ServerPlayer> recur$noClonesDeep(List<ServerPlayer> players) { return withoutClones(players); }

	private static List<ServerPlayer> withoutClones(List<ServerPlayer> players) {
		for (ServerPlayer p : players) {
			if (p instanceof FakePlayer) return players.stream().filter(q -> !(q instanceof FakePlayer)).toList();
		}
		return players;
	}
}
