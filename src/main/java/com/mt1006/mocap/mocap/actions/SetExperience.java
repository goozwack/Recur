package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** A recorded player's experience level. Nothing shows it: it's the experience their clone drops when it's killed. */
public class SetExperience implements ComparableAction
{
	private final int level;

	public SetExperience(Entity entity)
	{
		level = entity instanceof Player player ? player.experienceLevel : -1;
	}

	public SetExperience(RecordingFiles.Reader reader)
	{
		level = reader.readVarInt();
	}

	@Override public boolean differs(ComparableAction action)
	{
		return level != ((SetExperience)action).level;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (level < 0 || (action != null && !differs(action))) { return; }
		writer.addByte(Type.SET_EXPERIENCE.id);
		writer.addVarInt(level);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (ctx.entity != ctx.mainEntity) { return Result.IGNORED; }
		ctx.experienceLevel = level;
		return Result.OK;
	}
}
