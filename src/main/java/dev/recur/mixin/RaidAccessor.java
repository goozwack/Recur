package dev.recur.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Mixin(Raid.class)
public interface RaidAccessor {
	@Accessor("groupRaiderMap") Map<Integer, Set<Raider>> recur$groupRaiderMap();
	@Accessor("totalHealth") void recur$setTotalHealth(float health);
	@Accessor("groupsSpawned") void recur$setGroupsSpawned(int groups);
	@Accessor("started") void recur$setStarted(boolean started);
	@Accessor("raidCooldownTicks") void recur$setRaidCooldownTicks(int ticks);
	@Accessor("waveSpawnPos") void recur$setWaveSpawnPos(Optional<BlockPos> pos);
	@Invoker("setDirty") void recur$setDirty(ServerLevel level);
	@Invoker("playSound") void recur$playSound(ServerLevel level, BlockPos pos);
}
