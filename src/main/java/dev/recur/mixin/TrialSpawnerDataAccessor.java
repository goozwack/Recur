package dev.recur.mixin;

import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

@Mixin(TrialSpawnerStateData.class)
public interface TrialSpawnerDataAccessor {
	@Accessor("detectedPlayers") Set<UUID> recur$detectedPlayers();
	@Accessor("currentMobs") Set<UUID> recur$currentMobs();
	@Accessor("totalMobsSpawned") int recur$totalMobsSpawned();
	@Accessor("totalMobsSpawned") void recur$setTotalMobsSpawned(int count);
}
