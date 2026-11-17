package dev.recur;

import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.utils.FakePlayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * The anti-dupe rules (timed resets, or normal loops with them switched on): what a clone gets must not become a real
 * item a second time.
 *
 * - Blocks: every block a recorded player mines (or blows up...) is mined again by their clone after each reset. So from
 *   then on that block drops nothing for anyone; every other block can be mined normally, clone-placed or grown ones too.
 * - Items a clone drops can't be picked up (by players, mobs or hoppers) and never merge with real items.
 * - Mobs a clone made (golems it built, babies of animals it bred) or killed drop no loot or experience; nor do clones.
 * (Items a clone takes out of containers: see ContainerDeltas.netRemovals.)
 */
public final class CloneMade {
	/** Tag on entities a clone made (and on mobs a clone's recorded change killed). */
	public static final String TAG = "recur_clone_made";

	/** Has the tag (also under its old Resync name, in worlds from before the rename). */
	private static boolean tagged(net.minecraft.world.entity.Entity e) {
		var tags = e.entityTags();
		return tags.contains(TAG) || tags.contains(Legacy.old(TAG));
	}

	/**
	 * Blocks some recording mined, and what they were: a clone mines them again every run, so they're its loot, not
	 * yours. (A different block there isn't broken by the clone, so that one drops normally.) null = unknown (older save).
	 */
	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Block>> MINED = new HashMap<>();
	/** Blocks mined this tick. They count from the next tick on, so the player mining one now still gets its drops. */
	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Block>> PENDING = new HashMap<>();
	/** What blocks were right before they changed this tick (drops come after the block is already gone). */
	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Block>> WAS = new HashMap<>();

	private CloneMade() {}

	public static boolean active() { return TimedReset.antiDupe(); }

	public static void clear() { MINED.clear(); PENDING.clear(); WAS.clear(); }

	/** Blocks recorded players mine are noted during timed resets and normal loops alike. */
	private static boolean tracking() { return TimedReset.isActive() || LoopManager.isActive(); }

	/** End of every server tick. */
	public static void tick() {
		if (!WAS.isEmpty()) WAS.clear();
		if (PENDING.isEmpty()) return;
		PENDING.forEach((dim, map) -> MINED.computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>()).putAll(map));
		PENDING.clear();
	}

	/** Before Level.setBlock: remember what a block that matters here was. */
	public static void beforeSetBlock(Level level, BlockPos pos) {
		if (!tracking() || !(level instanceof ServerLevel)) return;
		var mined = MINED.get(level.dimension());
		ServerPlayer actor = WorldLog.currentActor();
		boolean recordingActor = actor != null && !(actor instanceof FakePlayer) && ReplayScope.depth <= 0;
		if (!recordingActor && (mined == null || !mined.containsKey(pos.asLong()))) return;
		var was = WAS.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
		if (!was.containsKey(pos.asLong())) was.put(pos.asLong(), level.getBlockState(pos).getBlock());
	}

	/** After Level.setBlock actually changed a block: a recorded player removing it means their clone will too. */
	public static void afterSetBlock(Level level, BlockPos pos, boolean changed) {
		if (!changed || !tracking() || ReplayScope.depth > 0 || TimedReset.isRestoring() || !(level instanceof ServerLevel)) return;
		ServerPlayer actor = WorldLog.currentActor();
		if (actor == null || actor instanceof FakePlayer || !WorldLog.isRecording(actor)) return;
		BlockState now = level.getBlockState(pos);
		if (!now.isAir() && !(now.getBlock() instanceof LiquidBlock)) return;
		var was = WAS.get(level.dimension());
		Block block = was != null ? was.get(pos.asLong()) : null;
		PENDING.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>()).put(pos.asLong(), block);
	}

	/** Block drops (Block.popResource / popResourceFromFace / popExperience): none from a block a clone mines. */
	public static boolean blocksDrops(Level level, BlockPos pos) {
		if (!active() || !(level instanceof ServerLevel)) return false;
		var mined = MINED.get(level.dimension());
		if (mined == null || !mined.containsKey(pos.asLong())) return false;
		Block minedBlock = mined.get(pos.asLong());
		if (minedBlock == null) return true;
		// The block here (or, if it's already gone, what it was a moment ago).
		BlockState now = level.getBlockState(pos);
		Block here = now.getBlock();
		if (now.isAir() || here instanceof LiquidBlock) {
			var was = WAS.get(level.dimension());
			Block before = was != null ? was.get(pos.asLong()) : null;
			if (before != null) here = before;
		}
		return here == minedBlock;
	}

	private static final String MARK = "recur_clone";

	/** A clone's item (dropped by a clone, or one a mob picked up from a clone): never real. */
	public static boolean isCloneItem(Entity e) {
		if (!active() || !(e instanceof ItemEntity item)) return false;
		return e.entityTags().contains(Playing.MOCAP_ENTITY_TAG) || isMarked(item.getItem());
	}

	public static boolean isMarked(net.minecraft.world.item.ItemStack stack) {
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		return data != null && (data.copyTag().contains(MARK) || data.copyTag().contains(Legacy.old(MARK)));
	}

	/** Marks a clone's dropped item, so wherever it ends up (a piglin's hand, an allay, a fox...) it stays a clone's. */
	public static void markIfClone(Entity e) {
		if (!active() || !(e instanceof ItemEntity item) || !e.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) return;
		net.minecraft.world.item.ItemStack stack = item.getItem();
		if (stack.isEmpty() || isMarked(stack)) return;
		net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, t -> t.putBoolean(MARK, true));
	}

	/** An entity was just added: a clone's item can't be picked up; what a clone's replay made is clone-made. */
	public static void onFresh(Entity e) {
		if (!active()) return;
		if (e instanceof ItemEntity && e.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) {
			markIfClone(e); // Mobs may pick it up as they did in the original run, but players and hoppers can't.
			return;
		}
		if (ReplayScope.depth > 0 && e instanceof LivingEntity && !(e instanceof FakePlayer)
			&& !e.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) e.addTag(TAG);
	}

	/** Breaking / killing this gives nothing (used for the red crosshair). */
	public static boolean givesNothing(Entity e) {
		if (!active()) return false;
		if (e instanceof FakePlayer || e.entityTags().contains(Playing.MOCAP_ENTITY_TAG) || tagged(e)) return true;
		if (e instanceof net.minecraft.world.entity.player.Player) return false;
		if (Reservations.cloneKillsLater(e)) return true;
		return e instanceof LivingEntity l && l.getLastHurtByPlayer() instanceof FakePlayer;
	}

	/** Entity.spawnAtLocation (what boats, minecarts, item frames, armor stands, mobs... drop): nothing from a clone's. */
	public static boolean dropsNothing(Entity e) {
		if (!active() || e instanceof net.minecraft.world.entity.player.Player) return false;
		return e.entityTags().contains(Playing.MOCAP_ENTITY_TAG) || tagged(e);
	}

	/** Death loot and experience: none from clones, clone-made mobs, or mobs a clone killed. */
	public static boolean blocksDeathLoot(LivingEntity e) {
		if (!active()) return false;
		if (e instanceof FakePlayer || tagged(e)) return true;
		if (e instanceof net.minecraft.world.entity.player.Player) return false; // a real player's things always go in their grave
		return e.getLastHurtByPlayer() instanceof FakePlayer || Reservations.cloneKillsLater(e);
	}

	/** Breeding: the baby of animals a clone fed (or of clone-made parents) is clone-made. */
	public static void onBred(net.minecraft.world.entity.animal.Animal parent, net.minecraft.world.entity.animal.Animal partner, Entity baby) {
		if (!active() || baby == null) return;
		if (parent.getLoveCause() instanceof FakePlayer || partner.getLoveCause() instanceof FakePlayer
			|| tagged(parent) || tagged(partner)) baby.addTag(TAG);
	}

	public static CompoundTag toTag() {
		tick();
		CompoundTag root = new CompoundTag();
		MINED.forEach((dim, map) -> {
			net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
			for (var e : map.long2ObjectEntrySet()) {
				CompoundTag t = new CompoundTag();
				t.putLong("p", e.getLongKey());
				if (e.getValue() != null) t.putString("b", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getValue()).toString());
				list.add(t);
			}
			root.put(dim.identifier().toString(), list);
		});
		return root;
	}

	public static void fromTag(CompoundTag root) {
		clear();
		for (String dim : root.keySet()) {
			var map = new Long2ObjectOpenHashMap<Block>();
			ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim));
			if (root.get(dim) instanceof net.minecraft.nbt.ListTag list) {
				for (var t : list) {
					if (!(t instanceof CompoundTag c)) continue;
					String b = c.getStringOr("b", "");
					map.put(c.getLongOr("p", 0), b.isEmpty() ? null
						: net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(Identifier.parse(b)).orElse(null));
				}
			} else {
				for (long p : root.getLongArray(dim).orElse(new long[0])) map.put(p, null); // Saves from older versions have no marker.
			}
			MINED.put(key, map);
		}
	}
}
