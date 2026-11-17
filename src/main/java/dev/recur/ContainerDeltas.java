package dev.recur;

import dev.recur.mixin.AbstractHorseAccessor;
import dev.recur.mixin.CompoundContainerAccessor;
import dev.recur.mixin.MountMenuAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * What a player puts into or takes out of containers (chests, barrels, furnaces, hoppers, shulker boxes, chest boats,
 * minecarts, donkeys...), recorded as the items added and removed. The whole inventory is never stored. When the clone replays
 * it, the same items are added/removed from whatever is in there now, so items other players put in (or took out)
 * since then are left alone. A clone can only take what is still there; what it adds that doesn't fit is dropped.
 *
 * Only clicks in the container screen count: items a furnace smelts or a hopper moves meanwhile are the world's doing,
 * and happen again by themselves.
 */
public final class ContainerDeltas {
	/** ender: the player's own ender chest (entity is the player). */
	private record Target(ServerLevel level, BlockPos pos, UUID entity, boolean ender) {
		Target(ServerLevel level, BlockPos pos, UUID entity) { this(level, pos, entity, false); }
	}

	/** One kind of item (count ignored) and how many of it. */
	private static final class Entry {
		final ItemStack key;
		int count;
		Entry(ItemStack key, int count) { this.key = key; this.count = count; }
	}

	/** Per player, per container: the net change of their clicks since the last flush. */
	private static final Map<UUID, Map<Target, List<Entry>>> PENDING = new HashMap<>();

	private static ServerPlayer clicking;
	private static Map<Target, Container> clickTargets;
	private static Map<Target, List<Entry>> before;

	private ContainerDeltas() {}

	public static void clear() { PENDING.clear(); clicking = null; clickTargets = null; before = null; }

	/** Before a container click / recipe-book fill is handled: count what's in the open containers. */
	public static void beforeClick(ServerPlayer player) {
		clicking = null;
		if (!WorldLog.isRecording(player) || !player.level().getServer().isSameThread()) return;
		Map<Target, Container> targets = targets(player);
		if (targets.isEmpty()) return;
		Map<Target, List<Entry>> counts = new HashMap<>();
		targets.forEach((t, c) -> counts.put(t, count(c)));
		clicking = player;
		clickTargets = targets;
		before = counts;
	}

	/** Called after the click. Whatever changed in those containers was this player's doing. */
	public static void afterClick(ServerPlayer player) {
		if (clicking != player || clickTargets == null) return;
		Map<Target, Container> targets = clickTargets;
		Map<Target, List<Entry>> was = before;
		clicking = null; clickTargets = null; before = null;
		Map<Target, List<Entry>> acc = PENDING.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
		targets.forEach((t, c) -> {
			List<Entry> now = count(c);
			for (Entry e : was.get(t)) add(now, e.key, -e.count);
			if (now.isEmpty()) return;
			List<Entry> list = acc.computeIfAbsent(t, k -> new ArrayList<>());
			for (Entry d : now) add(list, d.key, d.count);
		});
	}

	/** At the end of the tick, each container a player changed becomes one edit (kind 5). */
	static void flush(long now, BiConsumer<UUID, WorldLog.Edit> sink) {
		if (PENDING.isEmpty()) return;
		for (var byPlayer : PENDING.entrySet()) {
			for (var byTarget : byPlayer.getValue().entrySet()) {
				List<Entry> list = byTarget.getValue();
				if (list.isEmpty()) continue;
				Target t = byTarget.getKey();
				var ops = t.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
				ListTag items = new ListTag();
				for (Entry e : list) {
					CompoundTag c = new CompoundTag();
					c.put("i", ItemStack.CODEC.encodeStart(ops, e.key).getOrThrow());
					c.putInt("n", e.count);
					items.add(c);
				}
				CompoundTag data = new CompoundTag();
				data.put("items", items);
				if (t.ender) data.putBoolean("ender", true);
				BlockPos pos = t.pos;
				if (pos == null) {
					Entity en = t.level.getEntity(t.entity);
					pos = en != null ? en.blockPosition() : BlockPos.ZERO;
				}
				sink.accept(byPlayer.getKey(), new WorldLog.Edit(now, t.level.dimension().identifier().toString(), 5, pos, t.entity, data));
			}
		}
		PENDING.clear();
	}

	public static void discard(UUID player) { PENDING.remove(player); }

	/** The checkpoint this run started from (null = the loop start). */
	private static Checkpoint startedFrom;

	/** Entities the checkpoint saved (and so put back with their inventories as they were then). */
	private static java.util.Set<java.util.UUID> savedEntities = java.util.Set.of();

	public static void setCheckpoint(Checkpoint target) {
		startedFrom = target;
		java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
		if (target != null && target.entities != null) {
			for (String dim : target.entities.keySet()) collectIds(target.entities.getListOrEmpty(dim), ids);
		}
		savedEntities = ids;
	}

	private static void collectIds(net.minecraft.nbt.ListTag list, java.util.Set<java.util.UUID> ids) {
		for (Tag t : list) {
			if (!(t instanceof CompoundTag c)) continue;
			c.getIntArray("UUID").filter(a -> a.length == 4).ifPresent(a -> ids.add(net.minecraft.core.UUIDUtil.uuidFromIntArray(a)));
			collectIds(c.getListOrEmpty("Passengers"), ids);
		}
	}

	/**
	 * A change made before the checkpoint, to a container the checkpoint already put back exactly as it was then
	 * (block containers near a player, and every entity), is already in it: applying it again would add the items
	 * twice and spill whatever doesn't fit.
	 */
	private static boolean alreadyInCheckpoint(WorldLog.Edit e) {
		Checkpoint cp = startedFrom;
		if (cp == null || e.tick() > cp.timelineTick) return false;
		if (e.data().getBooleanOr("ender", false)) return true; // the checkpoint put the player back with their ender chest
		if (e.entity() != null) return savedEntities.contains(e.entity());
		for (Snapshot s : cp.snapshots.values()) if (s.covers(e.dim(), e.pos())) return true;
		return false;
	}

	/** Applies a recorded change: takes out what's still there, puts in what fits (and drops the rest). */
	static boolean apply(ServerLevel level, WorldLog.Edit e) {
		if (alreadyInCheckpoint(e)) return true;
		Container c;
		double x, y, z;
		if (e.data().getBooleanOr("ender", false)) {
			// A clone's ender chest is its player's: what it put in or took out is in theirs too. Not in timed resets,
			// where you keep your stuff (your ender chest already has it).
			if (LoopManager.isTimed() || e.entity() == null) return true;
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(e.entity());
			if (owner == null) return true;
			c = owner.getEnderChestInventory();
			x = owner.getX(); y = owner.getY() + 0.5; z = owner.getZ();
		} else if (e.entity() == null) {
			if (!level.isLoaded(e.pos())) return false; // try again when the chunk is loaded
			if (!(level.getBlockEntity(e.pos()) instanceof Container cc)) return true; // container is gone
			c = cc;
			x = e.pos().getX() + 0.5; y = e.pos().getY() + 0.5; z = e.pos().getZ() + 0.5;
		} else {
			Entity en = level.getEntity(e.entity());
			if (en == null) return false; // not loaded yet
			if (en instanceof AbstractHorse horse) c = ((AbstractHorseAccessor) horse).recur$inventory();
			else if (en instanceof Container cc) c = cc;
			else return true;
			if (c == null) return true;
			x = en.getX(); y = en.getY() + 0.5; z = en.getZ();
		}
		var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		for (Tag t : e.data().getListOrEmpty("items")) {
			if (!(t instanceof CompoundTag ct)) continue;
			ItemStack key = ItemStack.CODEC.parse(ops, ct.get("i")).result().orElse(ItemStack.EMPTY);
			int n = ct.getIntOr("n", 0);
			if (key.isEmpty() || n == 0) continue;
			if (n < 0) remove(c, key, -n);
			else {
				int left = insert(c, key, n);
				if (TimedReset.antiDupe()) left = 0; // Whatever doesn't fit would otherwise become a real copy of the player's items.
				while (left > 0) {
					int drop = Math.min(left, key.getMaxStackSize());
					ReplayScope.depth--; // what doesn't fit really drops (it isn't a recorded item)
					try { Containers.dropItemStack(level, x, y, z, key.copyWithCount(drop)); }
					finally { ReplayScope.depth++; }
					left -= drop;
				}
			}
		}
		c.setChanged();
		return true;
	}

	private static void remove(Container c, ItemStack key, int n) {
		for (int i = 0; i < c.getContainerSize() && n > 0; i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, key)) continue;
			int take = Math.min(n, s.getCount());
			s.shrink(take);
			if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
			n -= take;
		}
	}

	/** Returns how many didn't fit. */
	private static int insert(Container c, ItemStack key, int n) {
		int max = Math.min(key.getMaxStackSize(), c.getMaxStackSize(key));
		for (int i = 0; i < c.getContainerSize() && n > 0; i++) { // top up matching stacks first
			ItemStack s = c.getItem(i);
			if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, key) || s.getCount() >= max) continue;
			int put = Math.min(n, max - s.getCount());
			s.grow(put);
			n -= put;
		}
		for (int i = 0; i < c.getContainerSize() && n > 0; i++) { // then empty slots
			if (!c.getItem(i).isEmpty() || !c.canPlaceItem(i, key)) continue;
			int put = Math.min(n, max);
			c.setItem(i, key.copyWithCount(put));
			n -= put;
		}
		return n;
	}

	/** The containers behind the player's open screen: world containers and their ender chest (not their inventory). */
	private static Map<Target, Container> targets(ServerPlayer player) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu == player.inventoryMenu) return Map.of();
		Container mountContainer = null;
		Entity mount = null;
		if (menu instanceof AbstractMountInventoryMenu m) {
			mountContainer = ((MountMenuAccessor) m).recur$mountContainer();
			mount = ((MountMenuAccessor) m).recur$mount();
		}
		Map<Target, Container> out = new LinkedHashMap<>();
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Slot slot : menu.slots) add(player, slot.container, mountContainer, mount, out, seen);
		return out;
	}

	private static void add(ServerPlayer player, Container c, Container mountContainer, Entity mount,
							Map<Target, Container> out, Set<Container> seen) {
		if (c == null || c == player.getInventory() || !seen.add(c)) return;
		if (c == player.getEnderChestInventory()) {
			if (player.level() instanceof ServerLevel sl) out.put(new Target(sl, null, player.getUUID(), true), c);
			return;
		}
		if (c instanceof CompoundContainer cc) { // a double chest is two chests
			add(player, ((CompoundContainerAccessor) cc).recur$first(), mountContainer, mount, out, seen);
			add(player, ((CompoundContainerAccessor) cc).recur$second(), mountContainer, mount, out, seen);
		} else if (c instanceof BlockEntity be && be.getLevel() instanceof ServerLevel sl) {
			out.put(new Target(sl, be.getBlockPos().immutable(), null), c);
		} else if (c == mountContainer && mount != null && mount.level() instanceof ServerLevel sl) {
			out.put(new Target(sl, null, mount.getUUID()), c);
		} else if (c instanceof Entity en && en.level() instanceof ServerLevel sl) { // chest boats, minecarts
			out.put(new Target(sl, null, en.getUUID()), c);
		}
	}

	private static List<Entry> count(Container c) {
		List<Entry> list = new ArrayList<>();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty()) add(list, s, s.getCount());
		}
		return list;
	}

	private static void add(List<Entry> list, ItemStack item, int n) {
		if (n == 0) return;
		for (int i = 0; i < list.size(); i++) {
			Entry e = list.get(i);
			if (ItemStack.isSameItemSameComponents(e.key, item)) {
				e.count += n;
				if (e.count == 0) list.remove(i);
				return;
			}
		}
		list.add(new Entry(item.copyWithCount(1), n));
	}
}
