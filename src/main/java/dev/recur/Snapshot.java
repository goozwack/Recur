package dev.recur;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One player's exact state: full player data (inventory, health, XP, effects...), position, and the blocks around them. */
public final class Snapshot {
	public static final int BLOCK_RADIUS = 8;

	public final String dimension;
	public final double x, y, z;
	public final float yRot, xRot;
	public final CompoundTag playerTag;
	public final CompoundTag blocks;
	/** What the player was riding (a boat, minecart, horse...), or null. */
	public UUID vehicle;

	private Snapshot(String dimension, double x, double y, double z, float yRot, float xRot, CompoundTag playerTag, CompoundTag blocks) {
		this.dimension = dimension;
		this.x = x; this.y = y; this.z = z; this.yRot = yRot; this.xRot = xRot;
		this.playerTag = playerTag; this.blocks = blocks;
	}

	public static Snapshot capture(ServerPlayer player) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
		player.saveWithoutId(out);
		ServerLevel level = player.level();
		Snapshot s = new Snapshot(level.dimension().identifier().toString(),
			player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
			out.buildResult(), captureBlocks(level, player.blockPosition()));
		if (player.getVehicle() != null) s.vehicle = player.getVehicle().getUUID();
		return s;
	}

	private static CompoundTag captureBlocks(ServerLevel level, BlockPos center) {
		int r = BLOCK_RADIUS, size = 2 * r + 1;
		Map<BlockState, Integer> paletteIndex = new HashMap<>();
		ListTag palette = new ListTag();
		int[] data = new int[size * size * size];
		ListTag blockEntities = new ListTag();
		int i = 0;
		for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
			BlockPos pos = center.offset(dx, dy, dz);
			BlockState state = level.getBlockState(pos);
			Integer idx = paletteIndex.get(state);
			if (idx == null) {
				idx = palette.size();
				paletteIndex.put(state, idx);
				palette.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow());
			}
			data[i++] = idx;
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) {
				CompoundTag t = be.saveWithFullMetadata(level.registryAccess());
				t.putInt("lw_dx", dx); t.putInt("lw_dy", dy); t.putInt("lw_dz", dz);
				blockEntities.add(t);
			}
		}
		CompoundTag tag = new CompoundTag();
		tag.putInt("cx", center.getX()); tag.putInt("cy", center.getY()); tag.putInt("cz", center.getZ());
		tag.put("palette", palette);
		tag.putIntArray("data", data);
		tag.put("block_entities", blockEntities);
		return tag;
	}

	public ServerLevel level(MinecraftServer server) {
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
		ServerLevel level = server.getLevel(key);
		return level != null ? level : server.overworld();
	}

	/** True if this position is inside the area of blocks this snapshot saves and pastes back. */
	public boolean covers(String dim, BlockPos pos) {
		if (pos == null || !dimension.equals(dim)) return false;
		int r = BLOCK_RADIUS;
		return Math.abs(pos.getX() - blocks.getIntOr("cx", 0)) <= r && Math.abs(pos.getY() - blocks.getIntOr("cy", 0)) <= r
			&& Math.abs(pos.getZ() - blocks.getIntOr("cz", 0)) <= r;
	}

	/** Pastes the saved blocks back (call after the world reset). */
	public void restoreBlocks(ServerLevel level) {
		int cx = blocks.getIntOr("cx", 0), cy = blocks.getIntOr("cy", 0), cz = blocks.getIntOr("cz", 0);
		List<BlockState> palette = new ArrayList<>();
		for (Tag t : blocks.getListOrEmpty("palette")) {
			palette.add(BlockState.CODEC.parse(NbtOps.INSTANCE, t).result().orElse(Blocks.AIR.defaultBlockState()));
		}
		int[] data = blocks.getIntArray("data").orElse(new int[0]);
		int r = BLOCK_RADIUS, i = 0;
		for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
			if (i >= data.length) return;
			BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
			BlockState state = palette.get(data[i++]);
			if (level.isOutsideBuildHeight(pos)) continue;
			// Block entity side effects are skipped, because replacing a furnace or chest here must not spill its contents. They are restored below.
			if (level.getBlockState(pos) != state) level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
		}
		for (Tag t : blocks.getListOrEmpty("block_entities")) {
			if (!(t instanceof CompoundTag tag)) continue;
			BlockPos pos = new BlockPos(cx + tag.getIntOr("lw_dx", 0), cy + tag.getIntOr("lw_dy", 0), cz + tag.getIntOr("lw_dz", 0));
			// Load into the block entity the block already has: replacing it with a new object (chunk.setBlockEntity)
			// leaves it unregistered for ticking, so furnaces, hoppers, brewing stands... would stop working.
			BlockEntity existing = level.getBlockEntity(pos);
			if (existing != null) {
				existing.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
				existing.setChanged();
				level.sendBlockUpdated(pos, existing.getBlockState(), existing.getBlockState(), Block.UPDATE_ALL);
			} else {
				BlockEntity be = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, level.registryAccess());
				if (be != null) level.setBlockEntity(be); // registers its ticker
			}
		}
	}

	/** Restores the player's full state, then teleports them so clients recur. */
	public void restorePlayer(ServerPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		ServerLevel level = level(player.level().getServer());
		player.teleportTo(level, x, y, z, Set.of(), yRot, xRot, true);
		// The player entity lives on across resets, so clear what the save data doesn't cover or wouldn't overwrite.
		player.removeAllEffects();
		player.setArrowCount(0);
		player.setStingerCount(0);
		player.stopUsingItem();
		player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), playerTag));
		player.teleportTo(level, x, y, z, Set.of(), yRot, xRot, true);
		// Entity.load() switches the server-side game mode and abilities silently. The client has two separate copies of
		// the game mode: the HUD one (game event packet) and the tab-list one (player info packet), which decides whether
		// the client treats you as a spectator (no collision, invisible body). Both, plus abilities, must be resent.
		if (player.getHealth() <= 0f) player.setHealth(player.getMaxHealth());
		player.setCamera(player);
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
			net.minecraft.network.protocol.game.ClientboundGameEventPacket.CHANGE_GAME_MODE, player.gameMode.getGameModeForPlayer().getId()));
		player.level().getServer().getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
			net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, player));
		player.onUpdateAbilities();
		player.level().updateSleepingPlayerList();
		player.inventoryMenu.sendAllDataToRemote();
		player.containerMenu.sendAllDataToRemote();
		// load() picks the held slot silently: without this the game shows one item while the server uses another
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
	}

	/** Gets the player back on what they were riding (after the checkpoint's entities are back). */
	public void restoreVehicle(ServerPlayer player) {
		if (vehicle == null) return;
		net.minecraft.world.entity.Entity v = player.level().getEntity(vehicle);
		if (v != null && !v.isRemoved() && v.distanceToSqr(player) < 64) player.startRiding(v, true, true);
	}

	public CompoundTag toTag() {
		CompoundTag t = new CompoundTag();
		t.putString("dim", dimension);
		t.putDouble("x", x); t.putDouble("y", y); t.putDouble("z", z);
		t.putFloat("yRot", yRot); t.putFloat("xRot", xRot);
		t.put("player", playerTag); t.put("blocks", blocks);
		if (vehicle != null) t.putString("vehicle", vehicle.toString());
		return t;
	}

	public static Snapshot fromTag(CompoundTag t) {
		Snapshot s = new Snapshot(t.getStringOr("dim", "minecraft:overworld"), t.getDoubleOr("x", 0), t.getDoubleOr("y", 0), t.getDoubleOr("z", 0),
			t.getFloatOr("yRot", 0), t.getFloatOr("xRot", 0), t.getCompoundOrEmpty("player"), t.getCompoundOrEmpty("blocks"));
		String v = t.getStringOr("vehicle", "");
		if (!v.isEmpty()) { try { s.vehicle = UUID.fromString(v); } catch (IllegalArgumentException ignored) {} }
		return s;
	}
}
