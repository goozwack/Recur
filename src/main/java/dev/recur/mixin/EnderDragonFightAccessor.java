package dev.recur.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {
	@Accessor("dragonEvent")
	ServerBossEvent recur$dragonEvent();

	@Accessor("dragonKilled")
	boolean recur$dragonKilled();

	@Accessor("ticksSinceDragonSeen")
	void recur$setTicksSinceDragonSeen(int ticks);

	@Accessor("respawnStage")
	net.minecraft.world.level.dimension.end.DragonRespawnStage recur$respawnStage();

	@Accessor("respawnStage")
	void recur$setRespawnStage(net.minecraft.world.level.dimension.end.DragonRespawnStage stage);

	@Accessor("respawnTime")
	void recur$setRespawnTime(int time);
}
