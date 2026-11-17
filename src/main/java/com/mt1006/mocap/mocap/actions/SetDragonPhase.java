package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.jetbrains.annotations.Nullable;

/** The dragon's phase (circling, strafing, landing, breathing, charging...), so it does the same thing every run. */
public class SetDragonPhase implements ComparableAction
{
	private final int phase;

	public SetDragonPhase(Entity entity)
	{
		phase = entity instanceof EnderDragon dragon ? dragon.getPhaseManager().getCurrentPhase().getPhase().getId() : -1;
	}

	public SetDragonPhase(RecordingFiles.Reader reader)
	{
		phase = reader.readByte();
	}

	@Override public boolean differs(ComparableAction action)
	{
		return phase != ((SetDragonPhase)action).phase;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (action != null && !differs(action)) { return; }
		writer.addByte(Type.SET_DRAGON_PHASE.id);
		writer.addByte((byte)phase);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (!(ctx.entity instanceof EnderDragon dragon)) { return Result.IGNORED; }
		dev.recur.BossReplay.setPhase(dragon, phase);
		return Result.OK;
	}
}
