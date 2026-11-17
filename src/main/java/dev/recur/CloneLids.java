package dev.recur;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A clone "opening" a container only plays the lid animation and sound for a moment. It never really opens a menu:
 * a clone can't close one, so the container would count it as an opener forever (lid stuck open, and the lid
 * flipping the wrong way when you open and close it yourself).
 */
public final class CloneLids {
	private static final int OPEN_TICKS = 30;

	private record Open(ServerLevel level, BlockPos pos, int closeAt) {}

	private static final List<Open> OPEN = new ArrayList<>();
	private static int ticks;

	private CloneLids() {}

	/** True if the block at pos is a container whose opening was faked (so the real right click must be skipped). */
	public static boolean ghostOpen(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		BlockState state = level.getBlockState(pos);
		if (be instanceof ChestBlockEntity) {
			level.blockEvent(pos, state.getBlock(), 1, 1);
			play(level, pos, state.getBlock() instanceof ChestBlock chest ? chest.getOpenChestSound() : SoundEvents.CHEST_OPEN);
		} else if (be instanceof EnderChestBlockEntity) {
			level.blockEvent(pos, state.getBlock(), 1, 1);
			play(level, pos, SoundEvents.ENDER_CHEST_OPEN);
		} else if (be instanceof ShulkerBoxBlockEntity) {
			level.blockEvent(pos, state.getBlock(), 1, 1);
			play(level, pos, SoundEvents.SHULKER_BOX_OPEN);
		} else if (be instanceof BarrelBlockEntity && state.hasProperty(BarrelBlock.OPEN)) {
			level.setBlock(pos, state.setValue(BarrelBlock.OPEN, true), 3);
			play(level, pos, SoundEvents.BARREL_OPEN);
		} else if (be instanceof net.minecraft.world.Container) {
			return true; // furnaces, hoppers, dispensers...: nothing to animate, just don't open a menu
		} else {
			return false;
		}
		OPEN.add(new Open(level, pos.immutable(), ticks + OPEN_TICKS));
		return true;
	}

	public static void tick() {
		ticks++;
		for (Iterator<Open> it = OPEN.iterator(); it.hasNext(); ) {
			Open o = it.next();
			if (ticks < o.closeAt) continue;
			it.remove();
			close(o.level, o.pos);
		}
	}

	/** Puts the lid back to what the real (player) openers say, so a player who has it open keeps it open. */
	private static void close(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) return;
		BlockEntity be = level.getBlockEntity(pos);
		BlockState state = level.getBlockState(pos);
		if (be instanceof ChestBlockEntity chest) {
			level.blockEvent(pos, state.getBlock(), 1, 0);
			chest.recheckOpen();
			play(level, pos, state.getBlock() instanceof ChestBlock c ? c.getCloseChestSound() : SoundEvents.CHEST_CLOSE);
		} else if (be instanceof EnderChestBlockEntity ender) {
			level.blockEvent(pos, state.getBlock(), 1, 0);
			ender.recheckOpen();
			play(level, pos, SoundEvents.ENDER_CHEST_CLOSE);
		} else if (be instanceof ShulkerBoxBlockEntity) {
			level.blockEvent(pos, state.getBlock(), 1, 0);
			play(level, pos, SoundEvents.SHULKER_BOX_CLOSE);
		} else if (be instanceof BarrelBlockEntity barrel && state.hasProperty(BarrelBlock.OPEN)) {
			level.setBlock(pos, state.setValue(BarrelBlock.OPEN, false), 3);
			barrel.recheckOpen();
			play(level, pos, SoundEvents.BARREL_CLOSE);
		}
	}

	private static void play(ServerLevel level, BlockPos pos, SoundEvent sound) {
		level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5f, level.getRandom().nextFloat() * 0.1f + 0.9f);
	}

	public static void clear() { OPEN.clear(); }
}
