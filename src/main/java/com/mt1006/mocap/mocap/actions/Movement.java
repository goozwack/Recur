package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;

public class Movement implements ComparableAction
{
	private final double[] position = new double[3];
	private final float[] rotation = new float[2];
	private final boolean isOnGround;
	/** Compact form: 1/4096 of a block, the same steps the game itself moves entities on players' screens in. */
	static final double SCALE = 4096.0;
	static final float ANGLE = 65536f / 360f;
	private boolean hasRotation = true;
	/**
	 * Where the replay will have the entity once this was written compactly. Each step is measured from there and not
	 * from where it really was, so the rounding never adds up, however long it moves.
	 */
	private @Nullable double[] sent;

	public Movement(Entity entity)
	{
		Vec3 entityPos = entity.position();
		position[0] = entityPos.x;
		position[1] = entityPos.y;
		position[2] = entityPos.z;

		rotation[0] = entity.getXRot();
		rotation[1] = entity.getYRot();

		isOnGround = entity.onGround();
	}

	public Movement(RecordingFiles.Reader reader)
	{
		position[0] = reader.readDouble();
		position[1] = reader.readDouble();
		position[2] = reader.readDouble();

		rotation[0] = reader.readFloat();
		rotation[1] = reader.readFloat();

		isOnGround = reader.readBoolean();
	}

	public static Movement compact(RecordingFiles.Reader reader)
	{
		return new Movement(reader, true);
	}

	private Movement(RecordingFiles.Reader reader, boolean compact)
	{
		byte flags = reader.readByte();
		isOnGround = (flags & 1) != 0;
		if ((flags & 2) != 0)
		{
			boolean wide = (flags & 4) != 0;
			for (int i = 0; i < 3; i++) { position[i] = (wide ? reader.readInt() : reader.readShort()) / SCALE; }
		}
		hasRotation = (flags & 8) != 0;
		if (hasRotation)
		{
			rotation[0] = reader.readShort() / ANGLE;
			rotation[1] = reader.readShort() / ANGLE;
		}
	}

	static short angle(float degrees)
	{
		return (short)Math.round(net.minecraft.util.Mth.wrapDegrees(degrees) * ANGLE);
	}

	/**
	 * The small form: the step in 1/4096 blocks (two bytes each, four for a big jump) and the facing in 1/182 degree,
	 * only when it turned. About a third of the size, and finer than what players' screens show.
	 */
	public void writeCompact(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		Movement previous = (Movement)action;
		double[] base = previous == null ? position : previous.sent != null ? previous.sent : previous.position;
		if (previous != null && !differs(previous)) { sent = base; return; }

		long[] steps = new long[3];
		boolean moved = false, wide = false;
		for (int i = 0; i < 3; i++)
		{
			steps[i] = Math.round((position[i] - base[i]) * SCALE);
			if (Math.abs(steps[i]) > Integer.MAX_VALUE)
			{
				// teleported further than the small form can say: the full one
				write(writer, action);
				sent = new double[] { base[0] + position[0] - previous.position[0], base[1] + position[1] - previous.position[1],
					base[2] + position[2] - previous.position[2] };
				return;
			}
			if (steps[i] != 0) { moved = true; }
			if (steps[i] > Short.MAX_VALUE || steps[i] < Short.MIN_VALUE) { wide = true; }
		}
		boolean turned = previous == null || angle(rotation[0]) != angle(previous.rotation[0]) || angle(rotation[1]) != angle(previous.rotation[1]);
		if (previous != null && !moved && !turned && isOnGround == previous.isOnGround) { sent = base; return; } // less than a step

		writer.addByte(Type.MOVEMENT_COMPACT.id);
		writer.addByte((byte)((isOnGround ? 1 : 0) | (moved ? 2 : 0) | (wide ? 4 : 0) | (turned ? 8 : 0)));
		if (moved)
		{
			for (int i = 0; i < 3; i++)
			{
				if (wide) { writer.addInt((int)steps[i]); }
				else { writer.addShort((short)steps[i]); }
			}
		}
		if (turned)
		{
			writer.addShort(angle(rotation[0]));
			writer.addShort(angle(rotation[1]));
		}
		sent = new double[] { base[0] + steps[0] / SCALE, base[1] + steps[1] / SCALE, base[2] + steps[2] / SCALE };
	}

	public void writeAsHeader(RecordingFiles.Writer writer)
	{
		writer.addDouble(position[0]);
		writer.addDouble(position[1]);
		writer.addDouble(position[2]);

		writer.addFloat(rotation[1]);
		writer.addFloat(rotation[0]);
	}

	@Override public boolean differs(ComparableAction action)
	{
		return !Arrays.equals(position, ((Movement)action).position) ||
				!Arrays.equals(rotation, ((Movement)action).rotation) ||
				isOnGround != ((Movement)action).isOnGround;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (action != null && !differs(action)) { return; }
		writer.addByte(Type.MOVEMENT.id);

		if (action != null)
		{
			writer.addDouble(position[0] - ((Movement)action).position[0]);
			writer.addDouble(position[1] - ((Movement)action).position[1]);
			writer.addDouble(position[2] - ((Movement)action).position[2]);
		}
		else
		{
			writer.addDouble(0.0);
			writer.addDouble(0.0);
			writer.addDouble(0.0);
		}

		writer.addFloat(rotation[0]);
		writer.addFloat(rotation[1]);

		writer.addBoolean(isOnGround);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		//TODO: switch from relative to absolute
		// A clone's items move by themselves (see Controlled.take).
		if (ctx.entity != ctx.mainEntity && com.mt1006.mocap.mocap.playing.Controlled.loose(ctx.entity)) { return Result.OK; }
		Vec3 oldPos = ctx.entity.position();
		float yRot = hasRotation ? rotation[1] : ctx.entity.getYRot(), xRot = hasRotation ? rotation[0] : ctx.entity.getXRot();
		if (ctx.entity == ctx.mainEntity)
		{
			ctx.shiftPosition(position[0], position[1], position[2], yRot, xRot);
			// Fast: sent to everyone every tick, with its speed (see CloneMotion).
			if (dev.recur.CloneMotion.isFast(position[0], position[1], position[2]))
			{
				ctx.entity.setOnGround(isOnGround);
				ctx.entity.applyEffectsFromBlocks(oldPos, ctx.entity.position());
				dev.recur.CloneMotion.sendFast(ctx.level, ctx.entity, new Vec3(position[0], position[1], position[2]), isOnGround);
				return Result.OK;
			}
			ctx.entity.setDeltaMovement(Vec3.ZERO);
		}
		else
		{
			// The movement is added to where the recording had it, which ignores any drifting or pushing since.
			Vec3 base = com.mt1006.mocap.mocap.playing.Controlled.expectedOf(ctx.entity);
			if (base == null) { base = ctx.entity.position(); }
			double x = base.x + position[0];
			double y = base.y + position[1];
			double z = base.z + position[2];
			Vec3 glide = com.mt1006.mocap.mocap.playing.Controlled.glideOf(ctx.entity); // still gliding over (a boss)
			ctx.entity.snapTo(x + glide.x, y + glide.y, z + glide.z, yRot, xRot);
			com.mt1006.mocap.mocap.playing.Controlled.setExpectedOf(ctx.entity, new Vec3(x, y, z));
			// The dragon flaps its wings according to how fast it moves.
			if (ctx.entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
			{
				ctx.entity.setDeltaMovement(position[0], position[1], position[2]);
			}
		}

		ctx.entity.setOnGround(isOnGround);
		ctx.entity.applyEffectsFromBlocks(oldPos, ctx.entity.position());
		// A projectile is drawn by players' games from its speed, smoothly every frame. Sent only where it is, it jumps from
		// spot to spot each tick, so it gets its speed for this tick too, and its exact spot only now and then to stay on
		// track.
		if (ctx.entity != ctx.mainEntity && ctx.entity instanceof net.minecraft.world.entity.projectile.Projectile)
		{
			ctx.entity.setDeltaMovement(position[0], position[1], position[2]);
			ctx.level.getChunkSource().sendToTrackingPlayers(ctx.entity,
				new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(ctx.entity));
			if (ctx.recTick % 10 != 0) { return Result.OK; }
		}
		ctx.fluentMovement(() -> new ClientboundTeleportEntityPacket(ctx.entity.getId(), PositionMoveRotation.of(ctx.entity), Set.of(), isOnGround));
		return Result.OK;
	}
}
