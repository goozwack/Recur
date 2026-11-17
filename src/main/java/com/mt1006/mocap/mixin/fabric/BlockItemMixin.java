package com.mt1006.mocap.mixin.fabric;

import com.mt1006.mocap.events.BlockInteractionEvent;
import com.mt1006.mocap.mocap.recording.Recording;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class BlockItemMixin
{
	@Inject(method = "placeBlock", at = @At(value = "HEAD"))
	private void atPlaceBlock(BlockPlaceContext blockPlaceContext, BlockState blockState, CallbackInfoReturnable<Boolean> cir)
	{
		if (Recording.anyRecording() && !blockPlaceContext.getLevel().isClientSide())
		{
			BlockPos pos = blockPlaceContext.getClickedPos();

			BlockInteractionEvent.onBlockPlace(blockPlaceContext.getPlayer(),
					blockPlaceContext.getLevel().getBlockState(pos), blockState, pos);
		}
	}

	// Beds are plain BlockItems in 26.3: also record the head half, which the game places silently.
	@Inject(method = "placeBlock", at = @At(value = "TAIL"))
	private void atPlaceBlockEnd(BlockPlaceContext blockPlaceContext, BlockState blockState, CallbackInfoReturnable<Boolean> cir)
	{
		if (Recording.anyRecording() && !blockPlaceContext.getLevel().isClientSide())
		{
			if (!(blockState.getBlock() instanceof BedBlock) || blockState.getValue(BedBlock.PART) != BedPart.FOOT) { return; }

			Direction direction = blockState.getValue(BedBlock.FACING);
			BlockState headState = blockState.setValue(BedBlock.PART, BedPart.HEAD);
			BlockPos secondPos = blockPlaceContext.getClickedPos().relative(direction);

			BlockInteractionEvent.onSilentBlockPlace(blockPlaceContext.getPlayer(),
					blockPlaceContext.getLevel().getBlockState(secondPos), headState, secondPos);
		}
	}
}
