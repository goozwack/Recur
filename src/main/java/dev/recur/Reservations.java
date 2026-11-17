package dev.recur;

import dev.recur.mixin.CompoundContainerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Timed resets: items a clone takes out of something later this run can't be taken by anyone before it.
 *
 * - Containers (chests, barrels, furnaces, donkeys, chest minecarts...): clones put items in and take them out just as
 *   their players did. Whatever a clone takes out later this run is locked until then, whoever put it there (the world,
 *   another clone, a smelting furnace, you): a click that would leave fewer of that item than the clones still take is
 *   undone, hoppers can't pull them, and breaking the container doesn't drop them. Everything else can be taken (and
 *   whatever you take, your own clone takes from then on, so it's locked in later runs).
 * - Item frames and armor stands a clone takes from (or breaks) can't be used or hit until then; at that moment
 *   whatever the clone ended up taking disappears from them (and a frame it broke just goes, dropping nothing).
 */
public final class Reservations {
	/** Per container ("dim|pos" or "dim|uuid"): item (encoded, count 1) -> how many are reserved. */
	private static final Map<String, Map<CompoundTag, Integer>> CONTAINERS = new HashMap<>();
	/** Entities a clone changes later: until which run tick. */
	private static final Map<UUID, Long> ENTITIES = new HashMap<>();
	/** Containers a clone breaks later this run ("dim|pos" -> run tick): everything in them is locked until then. */
	private static final Map<String, Long> BROKEN = new HashMap<>();
	/** Mobs (and other entities) a clone kills later this run: killing them yourself gives nothing. */
	private static final java.util.Set<UUID> KILLS = new java.util.HashSet<>();

	private Reservations() {}

	public static boolean active() { return TimedReset.antiDupe(); }

	public static void clear() { CONTAINERS.clear(); ENTITIES.clear(); KILLS.clear(); BROKEN.clear(); LAST_SENT.clear(); }

	/** A recorded block change that leaves air or a liquid there: the clone breaks what's there. */
	private static boolean breaks(WorldLog.Edit e) {
		if (e.kind() != 0 || e.entity() != null) return false;
		String name = e.data().getCompoundOrEmpty("state").getStringOr("Name", "");
		return name.endsWith("air") || name.equals("minecraft:water") || name.equals("minecraft:lava");
	}

	/** A clone kills this later this run (its loot is the clone's). */
	public static boolean cloneKillsLater(Entity e) { return active() && KILLS.contains(e.getUUID()); }

	static String key(String dim, BlockPos pos, UUID entity) {
		return dim + "|" + (entity != null ? entity.toString() : Long.toString(pos.asLong()));
	}

	/**
	 * A clone's edits, as they'll be replayed in timed resets: everything it takes out of a container is reserved until
	 * then; for item frames / armor stands only its last change counts (and they're reserved until it).
	 */
	public static List<WorldLog.Edit> prepare(List<WorldLog.Edit> edits) {
		List<WorldLog.Edit> out = new ArrayList<>();
		Map<UUID, Integer> lastEntityEdit = new LinkedHashMap<>();
		for (WorldLog.Edit e : edits) {
			if (breaks(e)) BROKEN.merge(key(e.dim(), e.pos(), null), e.tick(), Math::max);
			if (e.kind() == 5) {
				Map<CompoundTag, Integer> reserved = CONTAINERS.computeIfAbsent(key(e.dim(), e.pos(), e.entity()), k -> new HashMap<>());
				for (Tag t : e.data().getListOrEmpty("items")) {
					if (!(t instanceof CompoundTag ct) || !(ct.get("i") instanceof CompoundTag item)) continue;
					int n = ct.getIntOr("n", 0);
					if (n < 0) reserved.merge(item, -n, Integer::sum);
				}
				out.add(e);
				continue;
			}
			// For item frames / armor stands only this clone's last change counts (which kind of entity it is, we find
			// out when it's applied); mark it.
			if ((e.kind() == 1 || e.kind() == 2) && e.entity() != null) lastEntityEdit.put(e.entity(), out.size());
			// A kill is recorded as the entity going away, or (a mob still in its death animation) saved with no health.
			if (e.entity() != null && (e.kind() == 2 || (e.kind() == 1 && e.data().contains("Health") && e.data().getFloatOr("Health", 1f) <= 0)))
				KILLS.add(e.entity());
			out.add(e);
		}
		for (int i : lastEntityEdit.values()) {
			WorldLog.Edit e = out.get(i);
			ENTITIES.merge(e.entity(), e.tick(), Math::max);
			CompoundTag data = e.data().copy();
			data.putBoolean("recur_last", true);
			out.set(i, new WorldLog.Edit(e.tick(), e.dim(), e.kind(), e.pos(), e.entity(), data));
		}
		return out;
	}

	/** A clone's container change is being applied now: what it takes out stops being reserved (it takes it now). */
	static void released(WorldLog.Edit e) {
		if (breaks(e)) {
			String k = key(e.dim(), e.pos(), null);
			Long until = BROKEN.get(k);
			if (until != null && until <= e.tick()) BROKEN.remove(k);
			return;
		}
		if (e.kind() != 5) return;
		Map<CompoundTag, Integer> reserved = CONTAINERS.get(key(e.dim(), e.pos(), e.entity()));
		if (reserved == null) return;
		for (Tag t : e.data().getListOrEmpty("items")) {
			if (!(t instanceof CompoundTag ct) || !(ct.get("i") instanceof CompoundTag item)) continue;
			int n = ct.getIntOr("n", 0);
			if (n < 0) reserved.computeIfPresent(item, (k, v) -> v + n > 0 ? v + n : null);
		}
	}

	public static boolean isHolder(Entity e) { return e instanceof HangingEntity || e instanceof ArmorStand; }

	/** A clone takes from / breaks this item frame or armor stand later this run. */
	public static boolean isReserved(Entity e) {
		return active() && isHolder(e) && ENTITIES.containsKey(e.getUUID());
	}

	/** Refuses players hitting or using a reserved one. */
	public static boolean refuse(net.minecraft.world.entity.player.Player player, Entity e) {
		if (!isReserved(e) || player instanceof com.mt1006.mocap.utils.FakePlayer || player.level().isClientSide()) return false;
		player.sendOverlayMessage(Component.literal("A clone takes this later."));
		return true;
	}

	/**
	 * Timed resets, an item frame / armor stand edit of a clone: only what it took away goes (it never adds anything).
	 * Returns false if it isn't one of those (the edit is applied normally then).
	 */
	static boolean applyHolderEdit(ServerLevel level, Entity entity, WorldLog.Edit e) {
		if (!isHolder(entity)) return false;
		Long until = ENTITIES.get(entity.getUUID());
		if (until != null && until <= e.tick()) ENTITIES.remove(entity.getUUID());
		if (!e.data().getBooleanOr("recur_last", e.data().getBooleanOr(Legacy.old("recur_last"), false))) return true; // only the clone's last change to it matters
		if (e.kind() == 2) { entity.discard(); return true; } // broken by the clone: gone, and nothing drops
		Entity after = entity.getType().create(level, EntitySpawnReason.LOAD);
		if (after == null) return true;
		after.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), e.data()));
		if (entity instanceof ItemFrame frame && after instanceof ItemFrame was) {
			if (!frame.getItem().isEmpty() && !ItemStack.isSameItemSameComponents(frame.getItem(), was.getItem())) frame.setItem(ItemStack.EMPTY);
		} else if (entity instanceof ArmorStand stand && after instanceof ArmorStand was) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack now = stand.getItemBySlot(slot);
				if (!now.isEmpty() && !ItemStack.isSameItemSameComponents(now, was.getItemBySlot(slot))) stand.setItemSlot(slot, ItemStack.EMPTY);
			}
		}
		after.discard();
		return true;
	}

	private static CompoundTag encode(ServerLevel level, ItemStack stack) {
		var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.encodeStart(ops, stack.copyWithCount(1)).result().map(t -> (CompoundTag) t).orElse(null);
	}

	/** A clone breaks this container later this run (and takes whatever is in it). */
	private static boolean brokenLater(Container c) {
		if (BROKEN.isEmpty() || !(c instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel l)) return false;
		return BROKEN.containsKey(key(l.dimension().identifier().toString(), be.getBlockPos(), null));
	}

	/** The reserved items of this container (block container or entity), or null. */
	private static Map<CompoundTag, Integer> reservedFor(Container c) {
		if (CONTAINERS.isEmpty()) return null;
		String key;
		if (c instanceof BlockEntity be && be.getLevel() instanceof ServerLevel l) key = key(l.dimension().identifier().toString(), be.getBlockPos(), null);
		else if (c instanceof Entity en && en.level() instanceof ServerLevel l) key = key(l.dimension().identifier().toString(), null, en.getUUID());
		else return null;
		Map<CompoundTag, Integer> r = CONTAINERS.get(key);
		return r == null || r.isEmpty() ? null : r;
	}

	private static ServerLevel levelOf(Container c) {
		if (c instanceof BlockEntity be && be.getLevel() instanceof ServerLevel l) return l;
		if (c instanceof Entity en && en.level() instanceof ServerLevel l) return l;
		return null;
	}

	private static int count(ServerLevel level, Container c, CompoundTag item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && item.equals(encode(level, s))) n += s.getCount();
		}
		return n;
	}

	/** Taking `amount` of the item in this slot would cut into what a clone takes later. */
	public static boolean blocksTake(Container c, int slot, int amount) {
		if (!active()) return false;
		if (c instanceof CompoundContainer cc) { // a double chest (hoppers see it whole)
			Container first = ((CompoundContainerAccessor) cc).recur$first();
			if (slot < first.getContainerSize()) return blocksTake(first, slot, amount);
			return blocksTake(((CompoundContainerAccessor) cc).recur$second(), slot - first.getContainerSize(), amount);
		}
		if (!c.getItem(slot).isEmpty() && brokenLater(c)) return true;
		Map<CompoundTag, Integer> reserved = reservedFor(c);
		if (reserved == null) return false;
		ServerLevel level = levelOf(c);
		ItemStack s = c.getItem(slot);
		if (s.isEmpty()) return false;
		CompoundTag item = encode(level, s);
		Integer r = item != null ? reserved.get(item) : null;
		return r != null && count(level, c, item) - amount < r;
	}

	/** The real containers behind a menu slot (a double chest is two). */
	private static Container containerOf(Slot slot, int[] index) {
		Container c = slot.container;
		index[0] = slot.getContainerSlot();
		if (c instanceof CompoundContainer cc) {
			Container first = ((CompoundContainerAccessor) cc).recur$first();
			if (index[0] < first.getContainerSize()) return first;
			index[0] -= first.getContainerSize();
			return ((CompoundContainerAccessor) cc).recur$second();
		}
		return c;
	}

	/** A container is broken (its items spill): what clones still take out of it vanishes instead of dropping. */
	public static void beforeSpill(Container c) {
		if (!active()) return;
		if (brokenLater(c)) { c.clearContent(); return; } // all of it was the clone's to break
		Map<CompoundTag, Integer> reserved = reservedFor(c);
		if (reserved == null) return;
		ServerLevel level = levelOf(c);
		for (var r : reserved.entrySet()) {
			int left = r.getValue();
			for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
				ItemStack s = c.getItem(i);
				if (s.isEmpty() || !r.getKey().equals(encode(level, s))) continue;
				int take = Math.min(left, s.getCount());
				s.shrink(take);
				if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
				left -= take;
			}
		}
	}

	/** Menu slots whose whole stack can't be taken. */
	public static int[] lockedSlots(ServerPlayer player) {
		AbstractContainerMenu menu = player.containerMenu;
		if (!active() || (CONTAINERS.isEmpty() && BROKEN.isEmpty()) || menu == null || menu == player.inventoryMenu) return new int[0];
		List<Integer> out = new ArrayList<>();
		int[] idx = new int[1];
		for (Slot slot : menu.slots) {
			if (slot.container == player.getInventory() || !slot.hasItem()) continue;
			Container c = containerOf(slot, idx);
			if (blocksTake(c, idx[0], slot.getItem().getCount())) out.add(slot.index);
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	private static ServerPlayer clicking;
	private static List<ItemStack> beforeSlots;
	private static ItemStack beforeCarried;

	/** Before a container click: if the screen has reserved items, remember everything (to undo the click). */
	public static void beforeClick(ServerPlayer player) {
		clicking = null;
		if (!active() || (CONTAINERS.isEmpty() && BROKEN.isEmpty()) || !player.level().getServer().isSameThread()) return;
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu == player.inventoryMenu || !hasReserved(menu, player)) return;
		clicking = player;
		beforeSlots = new ArrayList<>();
		for (Slot s : menu.slots) beforeSlots.add(s.getItem().copy());
		beforeCarried = menu.getCarried().copy();
	}

	private static boolean hasReserved(AbstractContainerMenu menu, ServerPlayer player) {
		int[] idx = new int[1];
		for (Slot s : menu.slots) {
			if (s.container == player.getInventory()) continue;
			Container c = containerOf(s, idx);
			if (reservedFor(c) != null || brokenLater(c)) return true;
		}
		return false;
	}

	/** Called after the click. If a reserved item is now short, the whole click is undone. */
	public static void afterClick(ServerPlayer player) {
		if (clicking != player) return;
		clicking = null;
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || beforeSlots == null || beforeSlots.size() != menu.slots.size()) return;
		boolean broken = false;
		int[] idx = new int[1];
		java.util.Set<Container> checked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Slot s : menu.slots) {
			if (s.container == player.getInventory()) continue;
			Container c = containerOf(s, idx);
			if (!checked.add(c)) continue;
			if (brokenLater(c)) {
				// Nothing may leave it, so compare with how its slots were before the click.
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.slots.get(i);
					if (slot.container == player.getInventory()) continue;
					int[] at = new int[1];
					if (containerOf(slot, at) != c) continue;
					ItemStack was = beforeSlots.get(i), now = slot.getItem();
					if (!was.isEmpty() && (now.isEmpty() || !ItemStack.isSameItemSameComponents(was, now) || now.getCount() < was.getCount())) broken = true;
				}
				continue;
			}
			Map<CompoundTag, Integer> reserved = reservedFor(c);
			if (reserved == null) continue;
			ServerLevel level = levelOf(c);
			for (var r : reserved.entrySet()) if (count(level, c, r.getKey()) < r.getValue()) broken = true;
		}
		if (broken) {
			for (int i = 0; i < menu.slots.size(); i++) menu.slots.get(i).set(beforeSlots.get(i));
			menu.setCarried(beforeCarried);
			menu.sendAllDataToRemote();
			player.sendOverlayMessage(Component.literal("A clone takes that later."));
		}
		beforeSlots = null;
		beforeCarried = null;
	}

	private static final Map<UUID, String> LAST_SENT = new HashMap<>();

	/** Every few ticks, tells each player with a container open which of its slots are locked. */
	public static void tick(MinecraftServer server) {
		if (!active() || server.getTickCount() % 4 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			AbstractContainerMenu menu = p.containerMenu;
			int id = menu != null ? menu.containerId : -1;
			int[] locked = lockedSlots(p);
			String sig = id + ":" + java.util.Arrays.toString(locked);
			if (sig.equals(LAST_SENT.get(p.getUUID()))) continue;
			LAST_SENT.put(p.getUUID(), sig);
			LoopNetworking.sendLocked(p, id, locked);
		}
	}

	/** What the crosshair is on gives nothing (asked by the client, to tint the crosshair red). */
	public static boolean noLoot(ServerPlayer player, int kind, long value) {
		if (!active()) return false;
		ServerLevel level = player.level();
		if (kind == 1) return CloneMade.blocksDrops(level, BlockPos.of(value));
		if (kind == 2) {
			Entity e = level.getEntity((int) value);
			return e != null && (isReserved(e) || CloneMade.givesNothing(e));
		}
		return false;
	}
}
