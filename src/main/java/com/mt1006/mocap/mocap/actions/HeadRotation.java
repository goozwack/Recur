package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

public class HeadRotation implements ComparableAction
{
	private final float headRotY;

	public HeadRotation(Entity entity)
	{
		headRotY = entity.getYHeadRot();
	}

	public HeadRotation(RecordingFiles.Reader reader)
	{
		headRotY = reader.readFloat();
	}

	private HeadRotation(float headRotY)
	{
		this.headRotY = headRotY;
	}

	public static HeadRotation compact(RecordingFiles.Reader reader)
	{
		return new HeadRotation(reader.readShort() / Movement.ANGLE);
	}

	/** The head turn as a short: 1/182 of a degree, far finer than the game sends it to players (1.4 degrees). */
	public void writeCompact(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (action != null && Movement.angle(headRotY) == Movement.angle(((HeadRotation)action).headRotY)) { return; }
		writer.addByte(Type.HEAD_ROTATION_COMPACT.id);
		writer.addShort(Movement.angle(headRotY));
	}

	@Override public boolean differs(ComparableAction action)
	{
		return headRotY != ((HeadRotation)action).headRotY;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (action != null && !differs(action)) { return; }
		writer.addByte(Type.HEAD_ROTATION.id);
		writer.addFloat(headRotY);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		ctx.entity.setYHeadRot(headRotY);
		ctx.fluentMovement(() -> new ClientboundRotateHeadPacket(ctx.entity, (byte)Math.floor(headRotY * 256.0f / 360.0f)));
		return Result.OK;
	}
}
