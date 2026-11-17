package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

public class PlaceBlock implements BlockAction
{
	private final BlockStateData previousBlockState;
	private final BlockStateData newBlockState;
	private final BlockPos blockPos;

	public PlaceBlock(BlockState previousBlockState, BlockState newBlockState, BlockPos blockPos)
	{
		this.previousBlockState = new BlockStateData(previousBlockState);
		this.newBlockState = new BlockStateData(newBlockState);
		this.blockPos = blockPos;
	}

	public PlaceBlock(RecordingFiles.Reader reader)
	{
		previousBlockState = new BlockStateData(reader);
		newBlockState = new BlockStateData(reader);
		blockPos = new BlockPos(reader.readInt(), reader.readInt(), reader.readInt());
	}

	public void write(RecordingFiles.Writer writer)
	{
		writer.addByte(Type.PLACE_BLOCK.id);

		previousBlockState.write(writer);
		newBlockState.write(writer);

		writer.addInt(blockPos.getX());
		writer.addInt(blockPos.getY());
		writer.addInt(blockPos.getZ());
	}

	@Override public void preExecute(Entity entity, Vec3i blockOffset)
	{
		previousBlockState.placeSilently(entity, blockPos.offset(blockOffset));
	}

	@Override public Result execute(PlayingContext ctx)
	{
		BlockPos pos = blockPos.offset(ctx.blockOffset);
		newBlockState.place(ctx.entity, pos);
		// The Wither is only summoned when a player places the last skull, so do that check for the clone too.
		net.minecraft.world.level.block.state.BlockState placed = ctx.level.getBlockState(pos);
		if (placed.is(net.minecraft.world.level.block.Blocks.WITHER_SKELETON_SKULL) || placed.is(net.minecraft.world.level.block.Blocks.WITHER_SKELETON_WALL_SKULL))
		{
			net.minecraft.world.level.block.WitherSkullBlock.checkSpawn(ctx.level, pos);
		}
		return Result.OK;
	}
}
