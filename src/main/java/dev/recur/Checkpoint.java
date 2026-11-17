package dev.recur;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A shared moment: one {@link Snapshot} for every player that was in the loop when it was created. */
public final class Checkpoint {
	public final int id;
	public String name;
	/** Tiny picture of the creator's view (THUMB_W x THUMB_H, RGB), or null. */
	public byte[] thumbnail;
	public static final int THUMB_W = 48, THUMB_H = 27;
	public final String creator;
	/** Position on the shared timeline (ticks) when created; clones are fast-forwarded to it when a run starts here. */
	public final long timelineTick;
	public final Map<UUID, Snapshot> snapshots;
	/** World clocks (time of day...) and weather when it was made, so loading it puts those back too. */
	public Map<String, Long> clocks = Map.of();
	public CompoundTag weather = new CompoundTag();
	/** Raids going on when it was made, by dimension (see StateRebind.captureRaids). */
	public CompoundTag raids = new CompoundTag();
	/** Every entity when it was made, by dimension (null for checkpoints from before this was saved). */
	public CompoundTag entities;
	/** Entities that had been destroyed by then (so their loop-start copies are removed again). */
	public java.util.Set<UUID> gone = java.util.Set.of();
	/** Position among the starred checkpoints (1 = top), or 0 = not starred. */
	public int star;
	/** True while the creator hasn't named it yet. Everyone else is asked to accept it once it has a name. */
	public transient boolean awaitingName;

	public Checkpoint(int id, String name, String creator, long timelineTick, Map<UUID, Snapshot> snapshots) {
		this.id = id; this.name = name; this.creator = creator; this.timelineTick = timelineTick; this.snapshots = snapshots;
	}

	/** Where this checkpoint is, as seen by the first snapshot (used for the list in the UI). */
	public Snapshot anchor(UUID preferred) {
		Snapshot s = snapshots.get(preferred);
		return s != null ? s : snapshots.values().stream().findFirst().orElse(null);
	}

	public CompoundTag toTag() {
		CompoundTag t = new CompoundTag();
		if (thumbnail != null) t.putByteArray("thumb", thumbnail);
		t.putInt("id", id); t.putString("name", name); t.putString("creator", creator); t.putLong("timeline", timelineTick);
		ListTag list = new ListTag();
		snapshots.forEach((uuid, snap) -> {
			CompoundTag e = snap.toTag();
			e.putString("uuid", uuid.toString());
			list.add(e);
		});
		t.put("snapshots", list);
		CompoundTag ct = new CompoundTag();
		clocks.forEach(ct::putLong);
		t.put("clocks", ct);
		t.put("weather", weather);
		t.put("raids", raids);
		t.putInt("star", star);
		if (entities != null) {
			t.put("entities", entities);
			long[] g = new long[gone.size() * 2];
			int i = 0;
			for (UUID u : gone) { g[i++] = u.getMostSignificantBits(); g[i++] = u.getLeastSignificantBits(); }
			t.putLongArray("gone", g);
		}
		return t;
	}

	public static Checkpoint fromTag(CompoundTag t) {
		Map<UUID, Snapshot> map = new LinkedHashMap<>();
		for (Tag e : t.getListOrEmpty("snapshots")) {
			if (e instanceof CompoundTag c) map.put(UUID.fromString(c.getStringOr("uuid", "")), Snapshot.fromTag(c));
		}
		Checkpoint c = new Checkpoint(t.getIntOr("id", 0), t.getStringOr("name", "Checkpoint"), t.getStringOr("creator", "?"),
			t.getLongOr("timeline", 0), map);
		c.thumbnail = t.getByteArray("thumb").orElse(null);
		Map<String, Long> clocks = new java.util.HashMap<>();
		CompoundTag ct = t.getCompoundOrEmpty("clocks");
		for (String key : ct.keySet()) clocks.put(key, ct.getLongOr(key, 0));
		c.clocks = clocks;
		c.weather = t.getCompoundOrEmpty("weather");
		c.raids = t.getCompoundOrEmpty("raids");
		c.star = t.getIntOr("star", 0);
		c.entities = t.getCompound("entities").orElse(null);
		long[] g = t.getLongArray("gone").orElse(new long[0]);
		java.util.Set<UUID> gone = new java.util.HashSet<>();
		for (int i = 0; i + 1 < g.length; i += 2) gone.add(new UUID(g[i], g[i + 1]));
		c.gone = gone;
		return c;
	}
}
