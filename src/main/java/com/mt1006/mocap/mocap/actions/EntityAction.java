package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

public class EntityAction implements Action
{
	private final int id;
	private final ComparableAction action;

	public EntityAction(int id, ComparableAction action)
	{
		this.id = id;
		this.action = action;
	}

	public int id() { return id; }

	public EntityAction(RecordingFiles.Reader reader)
	{
		this(reader, false);
	}

	public static EntityAction compact(RecordingFiles.Reader reader)
	{
		return new EntityAction(reader, true);
	}

	private EntityAction(RecordingFiles.Reader reader, boolean compact)
	{
		id = compact ? reader.readVarInt() : reader.readInt();
		Action readAction = Action.readAction(reader);
		if (readAction instanceof ComparableAction) { action = (ComparableAction)readAction; }
		else { action = ComparableAction.DUMMY; }
	}

	public void write(RecordingFiles.Writer writer, @Nullable ComparableAction previousAction)
	{
		// Written in place and taken back if the action had nothing to say (no temporary writer per action)
		int start = writer.size();
		writer.addByte(Type.ENTITY_ACTION_COMPACT.id);
		writer.addVarInt(id);
		int body = writer.size();
		// Movement and head turns are most of what's recorded, so they're stored small (and precisely enough that
		// nobody could tell, see Movement.writeCompact).
		if (action instanceof Movement movement) { movement.writeCompact(writer, previousAction); }
		else if (action instanceof HeadRotation head) { head.writeCompact(writer, previousAction); }
		else { action.write(writer, previousAction); }
		if (writer.size() == body) { writer.truncate(start); }
	}

	@Override public Result execute(PlayingContext ctx)
	{
		Entity entity = ctx.entityMap.get(id);
		if (entity == null) { return Result.IGNORED; }

		ctx.entity = entity;
		Result retVal = action.execute(ctx);
		ctx.entity = ctx.mainEntity;
		return retVal;
	}
}
