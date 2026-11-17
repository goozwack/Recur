package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class RightClickBlock implements BlockAction
{
	private final BlockHitResult blockHitResult;
	private final boolean offHand;

	public RightClickBlock(BlockHitResult blockHitResult, boolean offHand)
	{
		this.blockHitResult = blockHitResult;
		this.offHand = offHand;
	}

	public RightClickBlock(RecordingFiles.Reader reader)
	{
		Vec3 pos = new Vec3(reader.readDouble(), reader.readDouble(), reader.readDouble());
		BlockPos blockPos = new BlockPos(reader.readInt(), reader.readInt(), reader.readInt());
		Direction direction = directionFromByte(reader.readByte());
		boolean inside = reader.readBoolean();

		blockHitResult = new BlockHitResult(pos, direction, blockPos, inside);
		offHand = reader.readBoolean();
	}

	private Direction directionFromByte(byte val)
	{
		switch (val)
		{
			default: return Direction.DOWN;
			case 1: return Direction.UP;
			case 2: return Direction.NORTH;
			case 3: return Direction.SOUTH;
			case 4: return Direction.WEST;
			case 5: return Direction.EAST;
		}
	}

	private byte directionToByte(Direction direction)
	{
		switch (direction)
		{
			default: return 0;
			case UP: return 1;
			case NORTH: return 2;
			case SOUTH: return 3;
			case WEST: return 4;
			case EAST: return 5;
		}
	}

	public void write(RecordingFiles.Writer writer)
	{
		writer.addByte(Type.RIGHT_CLICK_BLOCK.id);

		writer.addDouble(blockHitResult.getLocation().x);
		writer.addDouble(blockHitResult.getLocation().y);
		writer.addDouble(blockHitResult.getLocation().z);

		writer.addInt(blockHitResult.getBlockPos().getX());
		writer.addInt(blockHitResult.getBlockPos().getY());
		writer.addInt(blockHitResult.getBlockPos().getZ());

		writer.addByte(directionToByte(blockHitResult.getDirection()));
		writer.addBoolean(blockHitResult.isInside());

		writer.addBoolean(offHand);
	}

	@Override public void preExecute(Entity entity, Vec3i blockOffset) {}

	@Override public Result execute(PlayingContext ctx)
	{
		if (!(ctx.entity instanceof Player)) { return Result.IGNORED; }

		BlockState blockState = ctx.level.getBlockState(blockHitResult.getBlockPos().offset(ctx.blockOffset));
		//InteractionHand interactionHand = offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

		//TODO: blockState.useItemOn (1.4)
		// A dragon egg teleports somewhere random when used. Where it went is in the recording, so it isn't used again.
		if (blockState.getBlock() instanceof net.minecraft.world.level.block.DragonEggBlock) { return Result.OK; }
		if (blockState.getBlock() instanceof net.minecraft.world.level.block.VaultBlock && ctx.level instanceof net.minecraft.server.level.ServerLevel vaultLevel)
		{
			// Opens it like a player would, as its player: a vault opens once per person (see TrialVaults).
			dev.recur.TrialVaults.cloneUse(vaultLevel, (Player)ctx.entity, blockHitResult.getBlockPos().offset(ctx.blockOffset), blockState);
			return Result.OK;
		}
		if (blockState.getBlock() instanceof BedBlock bed)
		{
			// A bed used where it can't be slept in (the Nether, the End) explodes. The blocks it breaks and the fire
			// are replayed from the recording, so this explosion only hurts and knocks back what's around.
			BlockPos pos = blockHitResult.getBlockPos().offset(ctx.blockOffset);
			if (!com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding && bed.getBedRule(ctx.level, pos).destroyOnUse())
			{
				if (blockState.getValue(BedBlock.PART) != net.minecraft.world.level.block.state.properties.BedPart.HEAD) { pos = pos.relative(blockState.getValue(BedBlock.FACING)); }
				net.minecraft.world.phys.Vec3 boom = net.minecraft.world.phys.Vec3.atCenterOf(pos);
				ctx.level.explode(null, ctx.level.damageSources().badRespawnPointExplosion(boom), null, boom, 5.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
			}
			return Result.OK;
		}
		// Containers only show the lid opening for a moment; a clone never holds a real menu open.
		if (ctx.level instanceof net.minecraft.server.level.ServerLevel serverLevel
				&& dev.recur.CloneLids.ghostOpen(serverLevel, blockHitResult.getBlockPos().offset(ctx.blockOffset))) { return Result.OK; }
		blockState.useWithoutItem(ctx.level, (Player)ctx.entity, blockHitResult);
		return Result.OK;
	}
}