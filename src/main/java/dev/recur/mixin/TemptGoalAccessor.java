package dev.recur.mixin;

import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The player a mob is following because of the food they hold (see MobTies). */
@Mixin(TemptGoal.class)
public interface TemptGoalAccessor {
	@Accessor("player")
	Player recur$player();
}
