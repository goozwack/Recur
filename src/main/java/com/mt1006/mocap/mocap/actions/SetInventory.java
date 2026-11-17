package com.mt1006.mocap.mocap.actions;

import com.mojang.serialization.DynamicOps;
import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a recorded player carries (their whole inventory, armor and offhand), written as the slots that changed.
 * Nothing shows it: it's what their clone drops when someone kills it (see LoopManager.killClone).
 */
public class SetInventory implements ComparableAction
{
	private static final String[] NONE = new String[0];
	/** Made from a player: every slot ("" = empty). */
	private final String[] slots;
	/** Read from a recording: the slots that changed and what's in them now. */
	private final int[] changed;
	private final String[] changedTo;

	public SetInventory(Entity entity)
	{
		changed = null;
		changedTo = null;
		if (!(entity instanceof Player player)) { slots = NONE; return; }
		Inventory inv = player.getInventory();
		int size = inv.getContainerSize();
		// This runs every tick, and turning an item into text is slow, so a slot is only encoded again when it changed.
		Cache cache = CACHE.computeIfAbsent(player, k -> new Cache());
		if (cache.stacks.length != size) { cache.stacks = new ItemStack[size]; cache.data = new String[size]; }
		slots = new String[size];
		DynamicOps<Tag> ops = null;
		for (int i = 0; i < size; i++)
		{
			ItemStack stack = inv.getItem(i);
			if (cache.data[i] != null && ItemStack.matches(cache.stacks[i], stack)) { slots[i] = cache.data[i]; continue; }
			String data = "";
			if (!stack.isEmpty())
			{
				if (ops == null) { ops = entity.registryAccess().createSerializationContext(NbtOps.INSTANCE); }
				try { data = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString(); }
				catch (Exception exception) { data = ""; }
			}
			cache.stacks[i] = stack.copy();
			cache.data[i] = data;
			slots[i] = data;
		}
	}

	public SetInventory(RecordingFiles.Reader reader)
	{
		slots = NONE;
		int count = reader.readVarInt();
		changed = new int[count];
		changedTo = new String[count];
		for (int i = 0; i < count; i++)
		{
			changed[i] = reader.readByte() & 0xFF;
			changedTo[i] = reader.readString();
		}
	}

	private static final java.util.Map<Player, Cache> CACHE = new java.util.WeakHashMap<>();

	private static final class Cache
	{
		ItemStack[] stacks = new ItemStack[0];
		String[] data = new String[0];
	}

	private static String slot(@Nullable String[] slots, int i)
	{
		return slots != null && i < slots.length ? slots[i] : "";
	}

	@Override public boolean differs(ComparableAction action)
	{
		String[] old = ((SetInventory)action).slots;
		if (old.length != slots.length) { return true; }
		for (int i = 0; i < slots.length; i++)
		{
			if (slots[i] != old[i] && !slots[i].equals(old[i])) { return true; }
		}
		return false;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (slots.length == 0) { return; }
		String[] old = action instanceof SetInventory previous ? previous.slots : null;
		int count = 0;
		for (int i = 0; i < slots.length; i++)
		{
			String was = slot(old, i);
			if (slots[i] != was && !slots[i].equals(was)) { count++; }
		}
		if (count == 0) { return; }
		writer.addByte(Type.SET_INVENTORY.id);
		writer.addVarInt(count);
		for (int i = 0; i < slots.length; i++)
		{
			String was = slot(old, i);
			if (slots[i] == was || slots[i].equals(was)) { continue; }
			writer.addByte((byte)i);
			writer.addString(slots[i]);
		}
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (changed == null || ctx.entity != ctx.mainEntity) { return Result.IGNORED; }
		if (ctx.inventory == null) { ctx.inventory = new String[256]; }
		for (int i = 0; i < changed.length; i++) { ctx.inventory[changed[i]] = changedTo[i]; }
		return Result.OK;
	}
}
