package dev.recur;

import com.mt1006.mocap.mocap.playing.Controlled;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Loot and experience a mob dropped when it died. In a recording they remember the mob, so in a replay they only drop
 * if that mob is there and dies in the replay too. When it isn't (a player killed it earlier, or it left its replay to
 * fight someone), the clone's kill gives nothing.
 */
public final class MobDrops {
	/** Key in an entity's recorded data: the mob that dropped it. */
	public static final String TAG = "recur_dropped_by";

	private static final ArrayDeque<Entity> DYING = new ArrayDeque<>();
	private static final Map<Entity, UUID> DROPPED_BY = new WeakHashMap<>();
	private static final Set<Entity> KILLED = Collections.newSetFromMap(new WeakHashMap<>());
	/** Key in an item's recorded data: the vault or trial spawner fight that gave it (see TrialVaults, TrialSpawns). */
	public static final String REWARD_TAG = "recur_reward";
	private static final ArrayDeque<String> REWARDS = new ArrayDeque<>();
	private static final Map<Entity, String> REWARD_OF = new WeakHashMap<>();

	private MobDrops() {}

	/** A mob is dropping its loot and experience (LivingEntity.dropAllDeathLoot). */
	public static void startDrops(LivingEntity mob) { DYING.push(mob); }

	public static void endDrops() { if (!DYING.isEmpty()) DYING.pop(); }

	/** An entity was just added. */
	public static void onFresh(Entity e) {
		if (!DYING.isEmpty() && (e instanceof ItemEntity || e instanceof ExperienceOrb)) DROPPED_BY.put(e, DYING.peek().getUUID());
		if (!REWARDS.isEmpty() && (e instanceof ItemEntity || e instanceof ExperienceOrb)) REWARD_OF.put(e, REWARDS.peek());
	}

	/** A vault or trial spawner is giving out its reward. */
	public static void startReward(String from) { REWARDS.push(from); }

	public static void endReward() { if (!REWARDS.isEmpty()) REWARDS.pop(); }

	public static String rewardOf(Entity e) { return REWARD_OF.isEmpty() ? null : REWARD_OF.get(e); }

	public static UUID droppedBy(Entity e) { return DROPPED_BY.isEmpty() ? null : DROPPED_BY.get(e); }

	/** A replay killed this mob. */
	public static void killedByReplay(Entity e) { KILLED.add(e); }

	/** Does what this recorded data is drop here? Anything that isn't a mob's drop does. */
	public static boolean dropsHere(PlayingContext ctx, CompoundTag data) {
		// a vault's or trial spawner's reward only comes if it gave it to the clone in this run too
		String reward = data.getStringOr(REWARD_TAG, "");
		if (reward.startsWith("v|")) return TrialVaults.openedFor(ctx.mainEntity, reward.substring(2));
		if (reward.startsWith("t|")) return TrialSpawns.replayed(reward.substring(2));
		// a killed clone's items and experience: only if that clone was killed again in this run
		if (reward.startsWith("c|")) return LoopManager.diedByReplay(reward.substring(2));
		String by = data.getStringOr(TAG, "");
		if (by.isEmpty()) return true;
		UUID id;
		try { id = UUID.fromString(by); } catch (IllegalArgumentException e) { return true; }
		Entity mob = ctx.aliases.get(id); // born in this run, under another id
		if (mob == null) mob = Controlled.alias(id);
		if (mob == null) mob = ctx.level.getEntity(id);
		if (mob == null || Controlled.hasLeft(mob)) return false;
		return Controlled.isControlled(mob) || KILLED.contains(mob);
	}

	public static void clear() { DYING.clear(); KILLED.clear(); }
}
