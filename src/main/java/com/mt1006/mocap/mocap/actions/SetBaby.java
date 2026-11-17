package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/** Whether a mob is a baby, so one that was grown up in the recording is grown up in the replay too. */
public class SetBaby implements ComparableAction
{
	private static final byte NOT_A_MOB = 0, ADULT = 1, BABY = 2;
	private final byte state;
	/** True while a recording sets a mob's age (a played back mob doesn't age by itself, see ReplayAgeMixin). */
	public static boolean applying;

	public SetBaby(Entity entity)
	{
		state = entity instanceof Mob mob ? (mob.isBaby() ? BABY : ADULT) : NOT_A_MOB;
	}

	public SetBaby(RecordingFiles.Reader reader)
	{
		state = reader.readByte();
	}

	@Override public boolean differs(ComparableAction action)
	{
		return state != ((SetBaby)action).state;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (state == NOT_A_MOB || (action != null && !differs(action))) { return; }
		writer.addByte(Type.SET_BABY.id);
		writer.addByte(state);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (state == NOT_A_MOB || !(ctx.entity instanceof Mob mob)) { return Result.IGNORED; }
		set(mob, state == BABY);
		return Result.OK;
	}

	/** Makes it a baby or grown up, leaving its age alone if it already is. */
	public static void set(Mob mob, boolean baby)
	{
		if (mob.isBaby() == baby) { return; }
		applying = true;
		try { mob.setBaby(baby); }
		finally { applying = false; }
	}
}
