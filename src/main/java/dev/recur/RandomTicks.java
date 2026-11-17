package dev.recur;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps growth consistent between runs. A random tick on a growing block a player changed (crop, sapling, farmland,
 * sugar cane...) is recorded as the exact list of blocks it changed, including a whole tree, together with what those
 * blocks were before. When that player's clone plays, the same block's random ticks aren't rolled again; the recorded
 * change is applied at the same moment instead, but only if every block it touches is still what it was then (so a
 * sapling with dirt placed over it won't grow). As soon as a live player changes such a block, it is theirs again.
 */
public final class RandomTicks {
	/** Who changed each block this run (their random ticks get recorded). */
	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<UUID>> OWNER = new HashMap<>();
	/** Blocks whose random ticks come from a playing clone's recording. */
	private static final Map<ResourceKey<Level>, LongOpenHashSet> CLAIMED = new HashMap<>();

	// The random tick being recorded right now: every block it changes, with its state before and after.
	private static boolean capturing;
	private static ServerLevel capLevel;
	private static BlockPos capOrigin;
	private static UUID capOwner;
	private static final Long2ObjectLinkedOpenHashMap<BlockState[]> CHANGES = new Long2ObjectLinkedOpenHashMap<>();

	private RandomTicks() {}

	/** Only blocks whose random ticks change something worth repeating; grass spreading, leaves decaying etc. are left alone. */
	private static final java.util.Map<Block, Boolean> TRACKS = new java.util.IdentityHashMap<>();

	/** Cached per block type, because this runs for every random tick in the world. */
	static boolean tracks(Block b) {
		Boolean t = TRACKS.get(b);
		if (t == null) TRACKS.put(b, t = computeTracks(b));
		return t;
	}

	private static boolean computeTracks(Block b) {
		return b instanceof CropBlock || b instanceof StemBlock || b instanceof SaplingBlock || b instanceof FarmlandBlock
			|| b instanceof SugarCaneBlock || b instanceof CactusBlock || b instanceof BambooStalkBlock
			|| b instanceof BambooSaplingBlock || b instanceof GrowingPlantHeadBlock || b instanceof VineBlock
			|| b instanceof SweetBerryBushBlock || b instanceof NetherWartBlock || b instanceof CocoaBlock
			|| b instanceof ChorusFlowerBlock || b instanceof PitcherCropBlock;
	}

	public static void touch(UUID player, ServerLevel level, BlockPos pos) {
		long p = pos.asLong();
		OWNER.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>()).put(p, player);
		LongOpenHashSet claimed = CLAIMED.get(level.dimension());
		if (claimed != null) claimed.remove(p); // a live player changed it: from now on it grows by their run
	}

	public static void forget(UUID player) {
		for (Long2ObjectOpenHashMap<UUID> m : OWNER.values()) m.values().removeIf(player::equals);
	}

	public static void claim(String dim, long pos) {
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim));
		CLAIMED.computeIfAbsent(key, k -> new LongOpenHashSet()).add(pos);
	}

	public static void clearClaims() { CLAIMED.clear(); }

	public static void clearAll() { OWNER.clear(); CLAIMED.clear(); capturing = false; CHANGES.clear(); }

	/** Before a random tick. Returns true to skip it (a clone's recording decides what happens to this block). */
	public static boolean before(BlockState state, ServerLevel level, BlockPos pos) {
		if (OWNER.isEmpty() && CLAIMED.isEmpty()) return false;
		if (!tracks(state.getBlock())) return false;
		ResourceKey<Level> dim = level.dimension();
		long p = pos.asLong();
		LongOpenHashSet claimed = CLAIMED.get(dim);
		if (claimed != null && claimed.contains(p)) return true;
		Long2ObjectOpenHashMap<UUID> owners = OWNER.get(dim);
		UUID owner = owners != null ? owners.get(p) : null;
		if (owner != null && !capturing) {
			capturing = true;
			capLevel = level;
			capOrigin = pos.immutable();
			capOwner = owner;
			CHANGES.clear();
		}
		return false;
	}

	/** After a random tick: if it was being recorded and changed anything, store it as an edit of the owner's run. */
	public static void after(ServerLevel level, BlockPos pos) {
		if (!capturing || level != capLevel || !pos.equals(capOrigin)) return;
		capturing = false;
		if (CHANGES.isEmpty()) return;
		ListTag list = new ListTag();
		Long2ObjectOpenHashMap<UUID> owners = OWNER.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
		for (var e : CHANGES.long2ObjectEntrySet()) {
			BlockState[] s = e.getValue();
			if (s[1] == null || s[0] == s[1]) continue;
			CompoundTag c = new CompoundTag();
			c.putLong("p", e.getLongKey());
			c.put("o", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, s[0]).getOrThrow());
			c.put("n", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, s[1]).getOrThrow());
			list.add(c);
			owners.put(e.getLongKey(), capOwner); // what grew (cane on top, a tree) belongs to the same run
		}
		CHANGES.clear();
		if (list.isEmpty()) return;
		CompoundTag data = new CompoundTag();
		data.put("c", list);
		WorldLog.addRandomTickEdit(capOwner, level, capOrigin, data);
	}

	/** Every block change while a random tick is recorded (Level.setBlock): remember the first old and the last new state. */
	public static void beforeSetBlock(Level level, BlockPos pos) {
		if (!capturing || level != capLevel) return;
		long p = pos.asLong();
		if (!CHANGES.containsKey(p)) CHANGES.put(p, new BlockState[] { level.getBlockState(pos), null });
	}

	public static void afterSetBlock(Level level, BlockPos pos) {
		if (!capturing || level != capLevel) return;
		BlockState[] s = CHANGES.get(pos.asLong());
		if (s != null) s[1] = level.getBlockState(pos);
	}

	/** Applies a recorded random tick only if every block it changed is still exactly what it was before it. */
	static void apply(ServerLevel level, CompoundTag data) {
		ListTag list = data.getListOrEmpty("c");
		BlockState[] olds = new BlockState[list.size()], news = new BlockState[list.size()];
		BlockPos[] at = new BlockPos[list.size()];
		for (int i = 0; i < list.size(); i++) {
			CompoundTag c = list.getCompoundOrEmpty(i);
			at[i] = BlockPos.of(c.getLongOr("p", 0));
			olds[i] = BlockState.CODEC.parse(NbtOps.INSTANCE, c.get("o")).result().orElse(null);
			news[i] = BlockState.CODEC.parse(NbtOps.INSTANCE, c.get("n")).result().orElse(null);
			if (olds[i] == null || news[i] == null || level.getBlockState(at[i]) != olds[i]) return;
		}
		for (int i = 0; i < at.length; i++) level.setBlock(at[i], news[i], Block.UPDATE_ALL);
	}
}
