package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * How much health a clone or a mob it plays back has, so it goes down when it's hit like it did (health bars from
 * mods like Jade, a raid's bar). It never goes down to nothing: dying is its own action.
 */
public class SetHealth implements ComparableAction
{
	private final float health;

	public SetHealth(Entity entity)
	{
		health = entity instanceof LivingEntity living ? living.getHealth() : -1f;
	}

	public SetHealth(RecordingFiles.Reader reader)
	{
		health = reader.readFloat();
	}

	@Override public boolean differs(ComparableAction action)
	{
		return health != ((SetHealth)action).health;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (health < 0f || (action != null && !differs(action))) { return; }
		writer.addByte(Type.SET_HEALTH.id);
		writer.addFloat(health);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (health <= 0f || !(ctx.entity instanceof LivingEntity living) || !living.isAlive()) { return Result.IGNORED; }
		// a boss takes the real damage players do to it instead (see BossReplay)
		if (dev.recur.BossReplay.isBoss(living)) { return Result.IGNORED; }
		float target = Math.min(health, living.getMaxHealth());
		// what players did to a trial spawner's mob in a clone's fight counts too (see TrialSpawns)
		float taken = dev.recur.TrialSpawns.damage(living);
		if (taken > 0) { target = Math.min(target, living.getMaxHealth() - taken); }
		if (target <= 0f) { return Result.OK; }
		if (living.getHealth() != target)
		{
			living.setHealth(target);
			raidBar(living);
		}
		return Result.OK;
	}

	/** A raider's health is part of its raid's bar. */
	static void raidBar(LivingEntity living)
	{
		if (living instanceof net.minecraft.world.entity.raid.Raider raider && raider.hasActiveRaid()) { raider.getCurrentRaid().updateBossbar(); }
	}
}
