package com.mt1006.mocap.mocap.recording;

import com.mt1006.mocap.command.CommandInfo;
import com.mt1006.mocap.mixin.fields.LevelMixin;
import com.mt1006.mocap.mocap.actions.EntityUpdate;
import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.settings.Settings;
import com.mt1006.mocap.utils.Utils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideable;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One recording session per player (upstream Mocap only supports a single global recording).
 * Sessions live in {@link #SESSIONS} from the moment they are started until they are saved or discarded.
 */
public class Recording
{
	public enum State
	{
		NOT_RECORDING,
		WAITING_FOR_ACTION,
		RECORDING,
		WAITING_FOR_DECISION
	}

	private static final Map<UUID, Recording> SESSIONS = new LinkedHashMap<>();

	public final RecordingFiles.Writer writer = new RecordingFiles.Writer();
	public State state = State.NOT_RECORDING;
	public ServerPlayer player; // Not final, see rebind
	private @Nullable ServerPlayer commandSourcePlayer = null;
	private @Nullable EntityState previousPlayerState = null;
	private @Nullable EntityState previousActions = null;
	private final Map<Entity, TrackedEntity> trackedEntityMap = new HashMap<>();
	private int trackedEntityCounter = 0;
	private int trackingTick = 0;
	private Entity playerVehicle;
	public int endsWithDeathPos = -1;
	// Where each tick's part of the recording ends, so a mob's earlier history can be put in later (see MobHistory)
	private int firstClock = -1;
	private int[] sectionEnds = new int[1024];
	private int sections;
	private final List<dev.recur.MobHistory.Shadow> toSplice = new ArrayList<>();

	private Recording(ServerPlayer player)
	{
		this.player = player;
	}

	/** The player got a new entity by leaving the End, so the recording follows the new one. */
	public static synchronized void rebind(ServerPlayer oldPlayer, ServerPlayer newPlayer)
	{
		Recording session = SESSIONS.get(oldPlayer.getUUID());
		if (session != null && session.player == oldPlayer) { session.player = newPlayer; }
	}

	public static synchronized @Nullable Recording of(@Nullable Entity entity)
	{
		if (!(entity instanceof ServerPlayer)) { return null; }
		Recording session = SESSIONS.get(entity.getUUID());
		return session != null && session.player == entity ? session : null;
	}

	/** The session of this entity, but only while it is actually recording. */
	public static @Nullable Recording active(@Nullable Entity entity)
	{
		Recording session = of(entity);
		return session != null && session.state == State.RECORDING ? session : null;
	}

	public static synchronized @Nullable Recording byEntityId(int entityId)
	{
		for (Recording session : SESSIONS.values())
		{
			if (session.state == State.RECORDING && session.player.getId() == entityId) { return session; }
		}
		return null;
	}

	public static synchronized boolean anyInState(State state)
	{
		for (Recording session : SESSIONS.values())
		{
			if (session.state == state) { return true; }
		}
		return false;
	}

	/** Cheap pre-check for mixins. */
	public static synchronized boolean anyRecording()
	{
		return anyInState(State.RECORDING);
	}

	public static synchronized List<Recording> sessions()
	{
		return new ArrayList<>(SESSIONS.values());
	}

	/** Drops a player's session without saving it (disconnects, loop stop). */
	public static synchronized void discard(UUID playerId)
	{
		Recording session = SESSIONS.remove(playerId);
		if (session != null) { session.writer.clear(); session.state = State.NOT_RECORDING; }
	}

	public static synchronized void discardAll()
	{
		for (Recording session : SESSIONS.values()) { session.writer.clear(); session.state = State.NOT_RECORDING; }
		SESSIONS.clear();
	}

	public static boolean isRecordedPlayer(@Nullable Entity entity)
	{
		return of(entity) != null;
	}

	/**
	 * Entities created while the loop runs (drops, projectiles, placed vehicles). Entities that are part of
	 * the world itself come back on their own after a reset, so recording them would give every clone a duplicate.
	 */
	public static final java.util.Set<java.util.UUID> FRESH_ENTITIES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public @Nullable TrackedEntity getTracked(Entity entity)
	{
		return trackedEntityMap.get(entity);
	}

	public static @Nullable TrackedEntity getTrackedEntity(Entity entity)
	{
		for (Recording session : sessions())
		{
			TrackedEntity tracked = session.trackedEntityMap.get(entity);
			if (tracked != null) { return tracked; }
		}
		return null;
	}

	/** Which session does a command refer to: the source player's, or the only one that exists. */
	private static synchronized @Nullable Recording resolve(CommandInfo commandInfo)
	{
		Entity source = commandInfo.source != null ? commandInfo.source.getEntity() : null;
		if (source instanceof ServerPlayer)
		{
			Recording session = SESSIONS.get(source.getUUID());
			if (session != null) { return session; }
		}
		return SESSIONS.size() == 1 ? SESSIONS.values().iterator().next() : null;
	}

	public static synchronized boolean start(CommandInfo commandInfo, ServerPlayer serverPlayer)
	{
		Recording session = SESSIONS.get(serverPlayer.getUUID());
		if (session != null && session.player != serverPlayer)
		{
			SESSIONS.remove(serverPlayer.getUUID());
			session = null;
		}
		if (session == null)
		{
			session = new Recording(serverPlayer);
			SESSIONS.put(serverPlayer.getUUID(), session);
		}
		return session.start(commandInfo);
	}

	private boolean start(CommandInfo commandInfo)
	{
		switch (state)
		{
			case NOT_RECORDING:
				commandSourcePlayer = commandInfo.source != null ? commandInfo.source.getPlayer() : null;
				commandInfo.sendSuccess("mocap.recording.start.waiting_for_action");
				state = State.WAITING_FOR_ACTION;
				break;

			case WAITING_FOR_ACTION:
				previousPlayerState = null;
				state = State.RECORDING;
				commandInfo.sendSuccess("mocap.recording.start.recording_started");
				break;

			case RECORDING:
				commandInfo.sendFailure("mocap.recording.start.already_recording");
				commandInfo.sendFailure("mocap.recording.start.already_recording.tip");
				return false;

			case WAITING_FOR_DECISION:
				commandInfo.sendFailure("mocap.recording.stop.waiting_for_decision");
				return false;
		}
		return true;
	}

	public static synchronized boolean stop(CommandInfo commandInfo)
	{
		if (Settings.RECORDING_SYNC.val) { Playing.stopAll(commandInfo); }

		Recording session = resolve(commandInfo);
		if (session == null)
		{
			commandInfo.sendFailure("mocap.recording.stop.server_not_recording");
			return false;
		}

		switch (session.state)
		{
			case NOT_RECORDING:
				commandInfo.sendFailure("mocap.recording.stop.server_not_recording");
				SESSIONS.remove(session.player.getUUID());
				return false;

			case WAITING_FOR_ACTION:
				commandInfo.sendSuccess("mocap.recording.stop.stop_waiting_for_action");
				session.state = State.NOT_RECORDING;
				SESSIONS.remove(session.player.getUUID());
				break;

			case RECORDING:
				commandInfo.sendSuccess("mocap.recording.stop.waiting_for_decision");
				session.state = State.WAITING_FOR_DECISION;
				break;

			case WAITING_FOR_DECISION:
				session.writer.clear();
				commandInfo.sendSuccess("mocap.recording.stop.recording_discarded");
				session.state = State.NOT_RECORDING;
				SESSIONS.remove(session.player.getUUID());
				break;
		}
		return true;
	}

	public static synchronized boolean save(CommandInfo commandInfo, String name)
	{
		Recording session = resolve(commandInfo);
		if (session == null)
		{
			commandInfo.sendFailure("mocap.recording.save.nothing_to_save");
			commandInfo.sendFailure("mocap.recording.save.nothing_to_save.tip");
			return false;
		}

		switch (session.state)
		{
			case NOT_RECORDING:
				commandInfo.sendFailure("mocap.recording.save.nothing_to_save");
				commandInfo.sendFailure("mocap.recording.save.nothing_to_save.tip");
				return false;

			case WAITING_FOR_ACTION:
				commandInfo.sendFailure("mocap.recording.save.waiting_for_action");
				commandInfo.sendFailure("mocap.recording.save.waiting_for_action.tip");
				return false;

			case RECORDING:
				commandInfo.sendFailure("mocap.recording.save.recording_not_stopped");
				commandInfo.sendFailure("mocap.recording.save.recording_not_stopped.tip");
				return false;

			case WAITING_FOR_DECISION:
				if (RecordingFiles.save(commandInfo, name, session.writer))
				{
					session.state = State.NOT_RECORDING;
					SESSIONS.remove(session.player.getUUID());
				}
				else { return false; }
				break;
		}
		return true;
	}

	public static synchronized boolean state(CommandInfo commandInfo)
	{
		if (SESSIONS.isEmpty())
		{
			commandInfo.sendSuccess("mocap.recording.state.not_recording");
			return true;
		}

		for (Recording session : SESSIONS.values())
		{
			switch (session.state)
			{
				case NOT_RECORDING:
					commandInfo.sendSuccess("mocap.recording.state.not_recording");
					break;

				case WAITING_FOR_ACTION:
					commandInfo.sendSuccess("mocap.recording.state.waiting_for_action");
					commandInfo.sendSuccess("mocap.recording.state.player_name", session.player.getName());
					break;

				case RECORDING:
					commandInfo.sendSuccess("mocap.recording.state.recording");
					commandInfo.sendSuccess("mocap.recording.state.player_name", session.player.getName());
					break;

				case WAITING_FOR_DECISION:
					commandInfo.sendSuccess("mocap.recording.state.waiting_for_decision");
					break;
			}
		}
		return true;
	}

	public static void onTick()
	{
		dev.recur.MobHistory.step();
		for (Recording session : sessions()) { session.tick(); }
	}

	/** Puts the histories of mobs this recording just took over back in the ticks they happened in. */
	private void spliceHistories()
	{
		record Part(int at, dev.recur.MobHistory.Shadow shadow, int index) {}
		List<Part> parts = new ArrayList<>();
		List<dev.recur.MobHistory.Shadow> spliced = new ArrayList<>(toSplice);
		for (dev.recur.MobHistory.Shadow shadow : toSplice)
		{
			for (int p = 0; p < shadow.parts(); p++)
			{
				int section = shadow.tickOf(p) - firstClock;
				if (section >= 0 && section < sections) { parts.add(new Part(sectionEnds[section], shadow, p)); }
			}
		}
		toSplice.clear();
		if (parts.isEmpty()) { return; }
		parts.sort(java.util.Comparator.comparingInt(Part::at)); // stable, so each mob's parts stay in order

		byte[] old = writer.raw();
		int oldSize = writer.size();
		int extra = 0;
		for (Part part : parts) { extra += part.shadow().length(part.index()); }
		for (dev.recur.MobHistory.Shadow shadow : spliced) { shadow.unpack(); }
		byte[] out = new byte[oldSize + extra + 1024];
		int from = 0, at = 0;
		for (Part part : parts)
		{
			System.arraycopy(old, from, out, at, part.at() - from);
			at += part.at() - from;
			from = part.at();
			at += part.shadow().copyPart(part.index(), out, at);
		}
		System.arraycopy(old, from, out, at, oldSize - from);
		at += oldSize - from;
		writer.replace(out, at);

		// every tick's end moves by what went in at or before it
		int added = 0, next = 0;
		for (int i = 0; i < sections; i++)
		{
			while (next < parts.size() && parts.get(next).at() <= sectionEnds[i]) { added += parts.get(next).shadow().length(parts.get(next).index()); next++; }
			sectionEnds[i] += added;
		}
	}

	private void tick()
	{
		if (state == State.WAITING_FOR_ACTION)
		{
			EntityState entityState = new EntityState(player);
			if (entityState.differs(previousActions))
			{
				previousPlayerState = null;
				state = State.RECORDING;
				Utils.sendSystemMessage(commandSourcePlayer, "mocap.recording.start.recording_started");

				previousActions = null;
				trackedEntityMap.clear();
				trackedEntityCounter = 0;
				trackingTick = 0;
				playerVehicle = null;
			}
			else
			{
				previousActions = entityState;
			}
		}

		if (state == State.RECORDING)
		{
			if (previousPlayerState == null)
			{
				firstClock = dev.recur.MobHistory.clock();
				sections = 0;
				toSplice.clear();
			}
			else
			{
				if (sections == sectionEnds.length) { sectionEnds = java.util.Arrays.copyOf(sectionEnds, sections * 2); }
				sectionEnds[sections++] = writer.size();
			}
			EntityState playerState = new EntityState(player);
			playerState.saveDifference(writer, previousPlayerState, this);
			previousPlayerState = playerState;

			if (player.isDeadOrDying() && Settings.RECORD_PLAYER_DEATH.val)
			{
				writer.modifyBoolean(endsWithDeathPos, true);
				Utils.sendSystemMessage(commandSourcePlayer, "mocap.recording.stop.waiting_for_decision");
				state = State.WAITING_FOR_DECISION;
				return;
			}

			if (Settings.ENTITY_TRACKING_DISTANCE.val == 0.0)
			{
				if (trackedEntityMap.size() > 0)
				{
					trackedEntityMap.forEach((uuid, trackedEntity) -> new EntityUpdate(EntityUpdate.REMOVE, trackedEntity.id).write(writer));
					trackedEntityMap.clear();
				}
				trackingTick++;
				return;
			}

			// Every entity is in exactly one recording, which belongs to the player who moves it (see EntityOwners).
			dev.recur.EntityOwners.claimVehicle(player);
			for (Entity entity : dev.recur.EntityOwners.owned(player))
			{
				if (entity.level() != player.level() || !dev.recur.EntityOwners.recordable(entity)) { continue; }

				TrackedEntity trackedEntity = trackedEntityMap.get(entity);
				// A boss an older clone is playing stays theirs: grabbing it only to let go a moment later stops it dead.
				if (trackedEntity == null && dev.recur.BossReplay.isBoss(entity)
					&& com.mt1006.mocap.mocap.playing.Controlled.isControlled(entity)) { continue; }
				if (trackedEntity == null)
				{
					// A mob seen since earlier in the run comes with all it did before (see MobHistory).
					dev.recur.MobHistory.Shadow shadow = entity instanceof net.minecraft.world.entity.Mob mob && firstClock >= 0
						? dev.recur.MobHistory.take(mob, firstClock) : null;
					if (shadow != null)
					{
						trackedEntity = new TrackedEntity(shadow.id, shadow.last);
						toSplice.add(shadow);
						dev.recur.WorldLog.adopt(player.getUUID(), shadow.effects());
					}
					else
					{
						trackedEntity = new TrackedEntity(trackedEntityCounter++);
						new EntityUpdate(EntityUpdate.ADD, trackedEntity.id, entity).write(writer);
					}
					trackedEntityMap.put(entity, trackedEntity);
				}
				trackedEntity.onTick(writer, entity, trackingTick);
			}

			if (!toSplice.isEmpty()) { spliceHistories(); }

			Entity newPlayerVehicle = player.getVehicle();
			if (newPlayerVehicle != null)
			{
				if (!newPlayerVehicle.equals(playerVehicle))
				{
					if (playerVehicle != null)
					{
						new EntityUpdate(EntityUpdate.PLAYER_DISMOUNT).write(writer);
						playerVehicle = null;
					}

					TrackedEntity trackedEntity = trackedEntityMap.get(newPlayerVehicle);
					if (trackedEntity != null) { new EntityUpdate(EntityUpdate.PLAYER_MOUNT, trackedEntity.id).write(writer); }
					else { new EntityUpdate(EntityUpdate.MOUNT_UUID, newPlayerVehicle.getUUID()).write(writer); } // Someone else's
					playerVehicle = newPlayerVehicle;
				}
			}
			else if (playerVehicle != null)
			{
				new EntityUpdate(EntityUpdate.PLAYER_DISMOUNT).write(writer);
				playerVehicle = null;
			}

			List<Entity> toRemove = new ArrayList<>();
			for (Map.Entry<Entity, TrackedEntity> entry : trackedEntityMap.entrySet())
			{
				if (entry.getValue().lastTick == trackingTick) { continue; }

				if (!entry.getValue().dying)
				{
					// It is still in the world because someone else moves it now or it was unloaded, so the clone lets go of it.
					Entity.RemovalReason reason = entry.getKey().getRemovalReason();
					byte updateType = reason == null || !reason.shouldDestroy() ? EntityUpdate.RELEASE
						: reason == Entity.RemovalReason.KILLED ? EntityUpdate.KILL : EntityUpdate.REMOVE;
					new EntityUpdate(updateType, entry.getValue().id).write(writer);
				}
				toRemove.add(entry.getKey());
			}
			toRemove.forEach(trackedEntityMap::remove);
			trackingTick++;
		}
	}
}
