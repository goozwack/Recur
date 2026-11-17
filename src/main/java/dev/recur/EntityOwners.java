package dev.recur;

import com.mt1006.mocap.mocap.playing.Controlled;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.recording.Recording;
import com.mt1006.mocap.mocap.settings.Settings;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PlayerRideable;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which live player's recording each entity belongs to. An entity is in exactly one recording at a time: whoever
 * moved it last. It starts with the player who made it (placed the boat, dropped the item), and moves to whoever
 * drives it, rides it alone, or hits/uses it. Its clone later moves it the same way; when ownership changed, the old
 * owner's clone lets go of it at that moment and the new owner's clone takes it over.
 */
public final class EntityOwners {
	/** How far from a player an entity nobody made on purpose (e.g. a mob's drop) can be to count as theirs. */
	private static final double NEARBY = 16;

	private static final Map<Entity, UUID> OWNER = new HashMap<>();
	private static final Map<UUID, Set<Entity>> OWNED = new HashMap<>();

	private EntityOwners() {}

	public static void clear() { OWNER.clear(); OWNED.clear(); }

	/** Entities a clone can place and move: vehicles, armor stands, item frames, paintings, dropped items, and modded seats. */
	public static boolean trackable(Entity e) {
		if (e instanceof Player) return false;
		if (e instanceof ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb) return Settings.TRACK_ITEM_ENTITIES.val;
		if (e instanceof VehicleEntity || e instanceof PlayerRideable) return Settings.TRACK_VEHICLE_ENTITIES.val;
		// Block-attached entities: paintings, item frames, leashes' knots and cushions.
		if (e instanceof ArmorStand || e instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity
			|| e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) return true;
		// What a player throws or shoots (arrows, tridents, pearls, snowballs, eggs, potions, fireworks, eyes of ender...):
		// their clone shows it flying the same way (a harmless copy, see ProjectileCopyMixin).
		if (e instanceof net.minecraft.world.entity.projectile.EyeOfEnder) return true;
		// An evoker's fangs, when the evoker is tied to a player.
		if (e instanceof net.minecraft.world.entity.projectile.EvokerFangs fangs) return fangs.getOwner() instanceof Mob caster && isOwned(caster);
		// What a mob tied to a player shoots (a skeleton's arrows) is in that player's recording too.
		if (e instanceof Projectile p) return (p.getOwner() instanceof ServerPlayer sp && !(sp instanceof com.mt1006.mocap.utils.FakePlayer))
			|| (p.getOwner() instanceof Mob shooter && isOwned(shooter));
		// The dragon's breath, from its fireballs or while it perches.
		if (e instanceof net.minecraft.world.entity.AreaEffectCloud c) return c.getOwner() instanceof Mob m && isOwned(m);
		// Modded entities without AI (cushions, chairs, seats...)
		return !(e instanceof LivingEntity) && !(e instanceof Projectile) && !e.getClass().getName().startsWith("net.minecraft.");
	}

	/** What a recording can move: everything trackable, and the mobs tied to its player (see MobTies). */
	public static boolean recordable(Entity e) { return trackable(e) || (e instanceof Mob && isOwned(e)); }

	private static boolean recording(ServerPlayer p) { return Recording.active(p) != null; }

	/** The player whose recording this entity is in, or null. */
	public static UUID ownerOf(Entity e) { return OWNER.isEmpty() ? null : OWNER.get(e); }

	/**
	 * Ties a mob to this player's recording (see MobTies). Forced (a direct action), it's taken from another player's
	 * recording or from the clone playing it back. Otherwise only a mob nobody has is taken.
	 */
	public static void claimMob(ServerPlayer p, Mob m, boolean force) {
		if (m.isRemoved() || !recording(p) || p.getUUID().equals(OWNER.get(m))) return;
		// A clone let go of it a moment ago: another clone is probably taking it right now (a player grabbing it for a
		// tick would leave a take-and-let-go in their recording that sets it free in later runs).
		if (!force && Controlled.justReleased(m)) return;
		if (Controlled.isControlled(m)) {
			if (!force) return;
			Controlled.markLeft(m);
			Controlled.release(m);
		} else if (OWNER.containsKey(m) && !force) {
			return;
		}
		set(m, p.getUUID());
	}

	/** An entity was just created: it belongs to the player whose action made it (or the nearest one, for items). */
	public static void onFresh(Entity e) {
		if (!LoopOverlay.active || e instanceof Player || e.entityTags().contains(Playing.MOCAP_ENTITY_TAG) || !trackable(e)) return;
		ServerPlayer owner = WorldLog.currentActor();
		// A block dropping its loot later by itself (sugar cane above a broken one, sand landing on a torch...): the world
		// does that again on its own when the clone repeats the cause, so recording it would make a second copy.
		if (owner == null && ReplayScope.blockDrop > 0) return;
		if (owner == null && e instanceof Projectile p && p.getOwner() instanceof ServerPlayer shooter && recording(shooter)) owner = shooter;
		// Shot by a mob tied to a player.
		if (owner == null && e instanceof net.minecraft.world.entity.TraceableEntity t && t.getOwner() instanceof Mob shooter && OWNER.get(shooter) != null
			&& e.level().getServer() != null) owner = e.level().getServer().getPlayerList().getPlayer(OWNER.get(shooter));
		if (owner == null && !(e instanceof Mob)) {
			double best = NEARBY * NEARBY;
			for (Player p : e.level().players()) {
				if (!(p instanceof ServerPlayer sp) || !recording(sp)) continue;
				double d = sp.distanceToSqr(e);
				if (d < best) { best = d; owner = sp; }
			}
		}
		if (owner != null && recording(owner)) set(e, owner.getUUID());
	}

	/** Driving a vehicle (or riding it alone) makes it yours. */
	public static void claimVehicle(ServerPlayer p) {
		Entity v = p.getVehicle();
		if (v == null) return;
		LivingEntity driver = v.getControllingPassenger();
		if (driver == p || (driver == null && v.getFirstPassenger() == p)) claim(p, v);
	}

	/** Hitting or using a vehicle, armor stand, item frame... makes it yours (mobs aren't moved by clones). */
	public static void touch(Player player, Entity e) {
		if (player instanceof ServerPlayer p && !(e instanceof Mob)) claim(p, e);
	}

	private static void claim(ServerPlayer p, Entity e) {
		if (e instanceof Player || e.isRemoved() || Controlled.isControlled(e) || !trackable(e) || !recording(p)) return;
		if (p.getUUID().equals(OWNER.get(e))) return;
		set(e, p.getUUID());
	}

	private static void set(Entity e, UUID owner) {
		drop(e);
		OWNER.put(e, owner);
		OWNED.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(e);
	}

	/** Nobody's any more (a clone took it over). The old owner's recording writes that it let go of it. */
	public static void drop(Entity e) {
		if (OWNER.isEmpty()) return;
		UUID old = OWNER.remove(e);
		if (old != null) {
			Set<Entity> set = OWNED.get(old);
			if (set != null) set.remove(e);
		}
	}

	public static boolean isOwned(Entity e) { return !OWNER.isEmpty() && OWNER.containsKey(e); }

	/** What this player's recording should track this tick (removed entities are forgotten). */
	public static Collection<Entity> owned(ServerPlayer p) {
		Set<Entity> set = OWNED.get(p.getUUID());
		if (set == null || set.isEmpty()) return List.of();
		List<Entity> out = new ArrayList<>(set.size());
		for (var it = set.iterator(); it.hasNext(); ) {
			Entity e = it.next();
			if (e.isRemoved()) { it.remove(); OWNER.remove(e); continue; }
			out.add(e);
		}
		return out;
	}

	/** The player's recording ended (or they left): their entities belong to nobody now. */
	public static void forget(UUID player) {
		Set<Entity> set = OWNED.remove(player);
		if (set != null) for (Entity e : set) OWNER.remove(e);
	}
}
