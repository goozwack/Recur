package dev.recur;

import com.mt1006.mocap.mocap.recording.Recording;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multiplayer time loop. Every player in the loop is recorded; when ANYONE dies (or the timer runs out) the whole
 * world resets and all recorded players come back as clones. A checkpoint is a snapshot of every player; the next
 * run only starts from it if all players vote for it, otherwise it starts from the loop start.
 */
public final class LoopManager {
	public static final int DEFAULT_TIMEOUT_TICKS = 20 * 60 * 20;
	/** A loop with no time limit: runs only end when someone dies. */
	public static final int NO_TIME_LIMIT = Integer.MAX_VALUE / 4;
	private static final int ENDING_WAIT_TICKS = 40;
	private static final int PERSISTED_START_DELAY = 40;

	private enum Phase { IDLE, AWAITING_PLAYERS, PLACING, RUNNING, ENDING, RESETTING }
	/** Give up waiting for the terrain after a minute (very slow world generation). */
	private static final int PLACING_MAX_TICKS = 1200;
	private static int placingTicks;
	private static Checkpoint placingTarget;
	/** The checkpoint the current run started from (null = the loop start). */
	private static Checkpoint runTarget;
	/** Who asked to reset once more and then cancel the loop (null if nobody did). */
	private static String cancelAfterReset;
	/** Players who are part of the current run (placed at its start, or joined during it). */
	private static final Set<UUID> IN_RUN = new java.util.HashSet<>();

	/** A past run of one player. startTick is its position on the shared timeline when its recording began. */
	/** endTick: where on the timeline the run ended (-1 = unknown, from older saves). */
	/** ghost: a player removed it during timed resets. It is no longer seen or heard, but it still takes what it took. */
	private record Clone(String recording, UUID owner, String ownerName, long startTick, String dimension, List<WorldLog.Edit> edits,
						 List<CloneChat.Line> chat, long endTick, boolean ghost) {
		Clone asGhost() { return new Clone(recording, owner, ownerName, startTick, dimension, edits, chat, endTick, true); }

		/** The dimension its player was in at this moment of the timeline (they may have gone through portals). */
		String dimensionAt(long tick) {
			String dim = dimension;
			long best = Long.MIN_VALUE;
			for (WorldLog.Edit e : edits) {
				if (e.kind() != 6 || e.tick() > tick || e.tick() < best) continue;
				best = e.tick();
				dim = e.data().getStringOr("to", dim);
			}
			return dim;
		}
	}
	/** A playing clone's player went through a portal at this moment: the clone does too. */
	private record DimChange(long tick, Clone past, String dim) {}

	/**
	 * A clone killed by a player. In every later run that clone dies at that moment too, so it plays until it dies. This
	 * only happens if its killer, which is a clone itself by then, is still alive at that moment. Recordings are named
	 * by file name.
	 */
	private record Kill(String killer, String victim, long tick) {}
	private static final List<Kill> KILLS = new ArrayList<>();
	/** Kills by players during this run (their recording only gets its name when the run ends). */
	private record LiveKill(UUID killer, String victim, long tick) {}
	private static final List<LiveKill> LIVE_KILLS = new ArrayList<>();
	/** Kills still to happen in this run. */
	private static final List<Kill> DUE_KILLS = new ArrayList<>();
	private static final List<DimChange> DIM_CHANGES = new ArrayList<>();
	private record PendingClone(Clone past, int atRunTick, String display) {}

	private static Phase phase = Phase.IDLE;
	/**
	 * Timed resets: every few minutes the world resets in place and everyone's recording becomes a clone, but players
	 * keep everything (inventory, position...) and carry on where they are; no loading screen, deaths are real deaths.
	 */
	private static boolean timed;
	private static String sessionId = "x";
	private static int runTicks;
	private static long timelineStart;
	private static int endingTicks;
	private static int awaitingTicks;
	private static int timeoutTicks = DEFAULT_TIMEOUT_TICKS;
	private static int recordingCounter;
	private static int runCounter;
	private static int checkpointCounter;
	private static int previousEffective = -1;

	private static final Set<UUID> PARTICIPANTS = new LinkedHashSet<>();
	private static final Map<UUID, String> NAMES = new HashMap<>();
	private static final Map<UUID, Snapshot> ORIGIN = new HashMap<>();
	private static final Map<UUID, Integer> VOTES = new HashMap<>();
	private static final Map<UUID, String> RUN_START_DIMENSION = new HashMap<>();
	/** Timeline tick at which each player's current recording began (differs from the run start for mid-run joiners). */
	private static final Map<UUID, Long> RECORDING_START = new HashMap<>();
	private static final List<Clone> CLONES = new ArrayList<>();
	private static final List<PendingClone> PENDING = new ArrayList<>();
	private static final List<Checkpoint> CHECKPOINTS = new ArrayList<>();
	/** Picture of the loop start (taken by whoever started the loop), or null. */
	private static byte[] startThumbnail;
	/** Checkpoints everyone agreed on, oldest first (-1 = the loop start). A deleted checkpoint's voters fall back to the latest. */
	private static final List<Integer> AGREED_HISTORY = new ArrayList<>();

	private LoopManager() {}

	public static boolean isActive() { return phase != Phase.IDLE; }
	public static boolean isTimed() { return phase != Phase.IDLE && timed; }
	/** Length of a run: the loop's timeout, or the time between timed resets. */
	private static int limit() { return timed ? TimedReset.intervalTicks() : timeoutTicks; }
	public static int cloneCount() { return liveClones(); }

	private static int liveClones() {
		int n = 0;
		for (Clone c : CLONES) if (!c.ghost) n++;
		return n;
	}
	public static int timeoutTicks() { return timeoutTicks; }
	public static void setTimeoutTicks(MinecraftServer server, int ticks) { timeoutTicks = Math.max(20, ticks); save(server); }
	/** Time left in this run. A run started from a checkpoint begins with the time that was left when it was made. */
	/** Ticks left in this run, or -1 when the loop has no time limit. */
	public static int remainingTicks() {
		if (!timed && timeoutTicks >= NO_TIME_LIMIT) return -1;
		return (int) Math.max(0, limit() - (timelineStart + runTicks));
	}

	// lifecycle

	public static void start(MinecraftServer server) { start(server, null); }

	/** Starts the loop. `starter` (or the first player, if null) is asked for a picture of the loop start. */
	public static void start(MinecraftServer server, ServerPlayer starter) {
		if (!WorldGuard.allowed() || TimedReset.isActive()) return;
		reset();
		timed = false;
		CloneMade.clear();
		sessionId = Long.toString(System.currentTimeMillis(), 36);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) addParticipant(p);

		// Everything on disk right now is the pristine world; from here on writes go to the overlay.
		server.saveEverything(false, true, true);
		LoopOverlay.active = true;
		TimedReset.beginTracking(server);
		WorldReset.setClockSnapshot(WorldReset.captureClocks(server));
		WorldReset.setWeatherSnapshot(StateRebind.captureWeather(server));

		timelineStart = 0;
		beginRun(server);
		save(server);
		ServerPlayer photographer = starter != null ? starter : online(server).stream().findFirst().orElse(null);
		if (photographer != null) LoopNetworking.sendPrompt(photographer, LoopNetworking.PROMPT_START_PICTURE, -1, "", "");
	}

	/**
	 * Starts timed resets: like the loop (everyone is recorded and comes back as clones), but every reset happens in
	 * place every few minutes, and players keep their stuff and stay where they are.
	 */
	public static boolean startTimed(MinecraftServer server, ServerPlayer starter) {
		if (phase != Phase.IDLE || !WorldGuard.allowed() || TimedReset.isActive()) return false;
		reset();
		timed = true;
		sessionId = Long.toString(System.currentTimeMillis(), 36);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) addParticipant(p);
		if (!TimedReset.start(server, starter)) { timed = false; return false; }
		timelineStart = 0;
		beginRun(server);
		save(server);
		return true;
	}

	/** /loop timed reset: the next timed reset happens right now. */
	public static void timedResetNow(MinecraftServer server) {
		if (isTimed() && phase == Phase.RUNNING) timedCycle(server, true);
	}

	/**
	 * A timed reset: everyone's recording so far becomes a clone, the world goes back to how it was (in place, while
	 * everyone stands in it) and all clones play again from the start, while new recordings begin where players are now.
	 */
	private static void timedCycle(MinecraftServer server, boolean keepRecordings) {
		for (ServerPlayer p : online(server)) {
			if (keepRecordings) saveClone(server, p);
			else { MocapBridge.discard(p.getUUID()); VoiceBridge.discard(p.getUUID()); CloneChat.discard(p.getUUID()); }
		}
		MocapBridge.stopAllPlayback();
		VoiceBridge.stopPlayback();
		CloneChat.clearScheduled();
		WorldLog.clearScheduled();
		CloneLids.clear();
		Recording.FRESH_ENTITIES.clear();
		clearEntityState();
		TimedReset.reset(server);
		timelineStart = 0;
		beginRun(server);
		save(server);
	}

	/** A dedicated server everyone left during timed resets: stop the clones and wait (the next player resets the world). */
	private static void pauseTimed(MinecraftServer server) {
		MocapBridge.stopAllPlayback();
		VoiceBridge.stopPlayback();
		CloneChat.clearScheduled();
		WorldLog.clearScheduled();
		CloneLids.clear();
		clearEntityState();
		phase = Phase.AWAITING_PLAYERS;
		awaitingTicks = 0;
		save(server);
	}

	/** The loop start's picture arrived (only the first one counts). */
	public static void onStartPicture(ServerPlayer player, byte[] data) {
		if (phase == Phase.IDLE || startThumbnail != null || !PARTICIPANTS.contains(player.getUUID())) return;
		if (data == null || data.length != Checkpoint.THUMB_W * Checkpoint.THUMB_H * 3) return;
		startThumbnail = data;
		MinecraftServer server = player.level().getServer();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) LoopNetworking.sendThumb(p, -1, data);
		save(server);
	}

	public static void stop(MinecraftServer server) {
		cancelAfterReset = null;
		if (phase == Phase.IDLE) return;
		MocapBridge.stopAllPlayback();
		clearEntityState();
		Recording.discardAll();
		WorldLog.clearAll();
		if (timed) TimedReset.stop(server); else { TimedReset.endTracking(); CloneMade.clear(); }
		timed = false;
		LoopOverlay.flushAll();
		phase = Phase.IDLE;
		deleteSaved(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) sendInactive(p);
	}

	/** The server is shutting down (or another world is about to load): drop every bit of in-memory loop state. */
	public static void shutdown() {
		try { MocapBridge.stopAllPlayback(); } catch (Exception ignored) {}
		clearEntityState();
		Recording.discardAll();
		WorldLog.clearAll();
		com.mt1006.mocap.mocap.playing.DataManager.RECUR_CACHE.clear();
		VoiceBridge.clear();
		CloneChat.clearAll();
		WorldReset.abort();
		LoopOverlay.dropAll();
		reset();
		placingTarget = null;
		phase = Phase.IDLE;
		timed = false;
	}

	/** Who moves which entity, and the checkpoint entity fix-ups, only last for one run. */
	private static void clearEntityState() {
		com.mt1006.mocap.mocap.playing.Controlled.clear();
		EntityOwners.clear();
		EntityRestore.clear();
		MobTies.clear();
		BossReplay.clear();
		MobHistory.clear();
		MobDrops.clear();
	}

	private static void sendInactive(ServerPlayer p) { sendIdleState(p); }

	/** No loop running: tells the client whether Recur is available here, and the timed-reset countdown (-1 = off). */
	public static void sendIdleState(ServerPlayer p) {
		LoopNetworking.sendState(p, new LoopNetworking.StatePayload(WorldGuard.allowed(), false, 0, 0, 1, 0, -1, -1, 0, 0, List.of(), -1));
	}

	private static void reset() {
		PARTICIPANTS.clear(); NAMES.clear(); ORIGIN.clear(); VOTES.clear(); RUN_START_DIMENSION.clear(); RECORDING_START.clear();
		CLONES.clear(); PENDING.clear(); CHECKPOINTS.clear(); IN_RUN.clear(); runTarget = null; cancelAfterReset = null;
		KILLS.clear(); LIVE_KILLS.clear(); DUE_KILLS.clear();
		startThumbnail = null; AGREED_HISTORY.clear();
		ContainerDeltas.setCheckpoint(null);
		recordingCounter = 0; runCounter = 0; checkpointCounter = 0; previousEffective = -1; runTicks = 0;
	}

	private static void addParticipant(ServerPlayer p) {
		PARTICIPANTS.add(p.getUUID());
		NAMES.put(p.getUUID(), p.getGameProfile().name());
		ORIGIN.put(p.getUUID(), Snapshot.capture(p));
		VOTES.put(p.getUUID(), -1);
	}

	private static List<ServerPlayer> online(MinecraftServer server) {
		List<ServerPlayer> list = new ArrayList<>();
		for (UUID id : PARTICIPANTS) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) list.add(p);
		}
		return list;
	}

	// events

	/** A participant chatted during a run: their clone will say it again (within the message limits). */
	public static void onChat(ServerPlayer player, String text) {
		if (phase != Phase.RUNNING || !PARTICIPANTS.contains(player.getUUID())) return;
		CloneChat.onChat(player.getUUID(), text, timelineStart + runTicks);
	}


	/**
	 * A looping player never really dies: the fatal hit is cancelled and the world resets instead. A real death would
	 * mean a death screen, a respawn and a new player entity, which breaks the recording and the client's state.
	 */
	public static boolean allowDeath(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
		if (phase == Phase.IDLE || !PARTICIPANTS.contains(player.getUUID())) return true;
		if (timed) return true; // timed resets: a death is a normal death (the items stay where you died)
		player.setHealth(player.getMaxHealth());
		if (phase != Phase.RUNNING) return false;
		// The death never really happens, so the recording is told: the clone dies here in later runs.
		MocapBridge.markDeath(player);
		phase = Phase.ENDING;
		endingTicks = 0;
		MinecraftServer server = player.level().getServer();
		return false;
	}

	/** Any participant dying ends the run for everybody. */
	public static void onPlayerDeath(ServerPlayer player) {
		if (phase != Phase.RUNNING || !PARTICIPANTS.contains(player.getUUID())) return;
		if (timed) {
			// What they did until now becomes a clone (it plays after the next reset); a new recording starts on respawn.
			MinecraftServer server = player.level().getServer();
			saveClone(server, player);
			save(server);
			syncAll(server);
			return;
		}
		phase = Phase.ENDING;
		endingTicks = 0;
		MinecraftServer server = player.level().getServer();
	}

	/** Timed resets: respawning after a death starts a new recording (its clone joins in at this moment of the run). */
	public static void onRespawn(ServerPlayer player) {
		if (!timed || phase != Phase.RUNNING || !PARTICIPANTS.contains(player.getUUID())) return;
		MinecraftServer server = player.level().getServer();
		RECORDING_START.put(player.getUUID(), timelineStart + runTicks);
		RUN_START_DIMENSION.put(player.getUUID(), player.level().dimension().identifier().toString());
		startRecording(server, player);
		syncAll(server);
	}

	public static void onJoin(MinecraftServer server, ServerPlayer player) {
		if (phase == Phase.IDLE) { sendInactive(player); return; }
		// A client only shows a player entity it has "player info" for, and each clone's info is sent when it spawns.
		// Someone joining later therefore needs the info of every clone already playing, or they would see none.
		for (ServerLevel l : server.getAllLevels()) {
			for (ServerPlayer fp : l.players()) {
				if (fp instanceof com.mt1006.mocap.utils.FakePlayer && !fp.isRemoved())
					player.connection.send(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(java.util.EnumSet.of(
						net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
						net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
						net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), java.util.List.of(fp)));
			}
		}
		if (!CLONE_ENTITY_IDS.isEmpty()) LoopNetworking.sendClones(player, new ArrayList<>(CLONE_ENTITY_IDS), ages(CLONE_ENTITY_IDS), false);
		for (Checkpoint c : CHECKPOINTS) LoopNetworking.sendThumb(player, c.id, c.thumbnail);
		LoopNetworking.sendThumb(player, -1, startThumbnail);
		boolean known = PARTICIPANTS.contains(player.getUUID());
		if (!known) {
			addParticipant(player);
			save(server);
		}
		switch (phase) {
			// Mid-reset, or waiting to start a resumed loop: wait with everyone else (spectating, loading screen).
			case RESETTING, AWAITING_PLAYERS -> { if (!timed) WorldReset.hold(player); }
			case PLACING -> {
				Snapshot snap = snapshotFor(placingTarget, player);
				LoopNetworking.sendReset(player, true);
				player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
				player.teleportTo(snap.level(server), snap.x, snap.y, snap.z, Set.of(), snap.yRot, snap.xRot, true);
			}
			case RUNNING -> {
				// Coming back from an earlier run (left before or during a reset): their saved state belongs to an old
				// timeline, so they start this run where it began for them. Rejoining the same run just carries on.
				if (!timed && known && !IN_RUN.contains(player.getUUID())) snapshotFor(runTarget, player).restorePlayer(player);
				IN_RUN.add(player.getUUID());
				if (!MocapBridge.isStillRecording(player)) {
					// Their recording, and so their later clone, begins at this moment in the run.
					RECORDING_START.put(player.getUUID(), timelineStart + runTicks);
					RUN_START_DIMENSION.put(player.getUUID(), player.level().dimension().identifier().toString());
					startRecording(server, player);
				}
			}
			default -> {}
		}
		syncAll(server);
	}

	/** Leaving (or quitting a singleplayer world) keeps what you did this run: it becomes a clone like a death would. */
	public static void onDisconnect(MinecraftServer server, ServerPlayer player) {
		if ((phase == Phase.RUNNING || phase == Phase.ENDING) && PARTICIPANTS.contains(player.getUUID())
			&& MocapBridge.isStillRecording(player)) {
			saveClone(server, player);
			save(server);
		}
		// They left before naming their new checkpoint, so everyone else is asked about it now.
		for (Checkpoint c : CHECKPOINTS) if (c.awaitingName && c.creator.equals(player.getGameProfile().name())) offerToEveryone(server, c, player);
		MocapBridge.discard(player.getUUID());
		VoiceBridge.discard(player.getUUID());
		CloneChat.discard(player.getUUID());
		ContainerDeltas.discard(player.getUUID());
		EntityOwners.forget(player.getUUID());
		if (phase != Phase.IDLE) syncAll(server);
	}

	// ticking

	/** A loop cancelled with a last reset: who did it and where the world is back at, until it stops. */
	private static String stopWho, stopWhere;
	private static int stopWaited;

	public static void onServerTick(MinecraftServer server) {
		if (stopWho != null && (EntityRestore.settled() || ++stopWaited > 200)) {
			String who = stopWho, where = stopWhere;
			stopWho = null;
			stop(server);
			broadcast(server, Component.literal(who + " cancelled the loop. The world is back at " + where + " and stays like that."));
		}
		WorldReset.tick(server);
		// /tick freeze: the loop's clock stops, so the timer, the clones' movement, chat, world changes and voice all wait.
		boolean frozen = !server.tickRateManager().runsNormally();
		if (frozen && phase == Phase.RUNNING) {
			VoiceBridge.onTick(timelineStart + runTicks, false);
			return;
		}
		VoiceBridge.onTick(timelineStart + runTicks + (phase == Phase.RUNNING ? 1 : 0), phase == Phase.RUNNING);
		if (phase == Phase.IDLE) return;

		switch (phase) {
			case AWAITING_PLAYERS -> {
				// The loop was loaded from a save and the world on disk is already pristine, so wait for someone to join and then start.
				if (online(server).isEmpty()) { awaitingTicks = 0; return; }
				if (++awaitingTicks >= PERSISTED_START_DELAY) {
					// Timed resets carry on with a reset where everyone is (the world on disk is untouched after a restart).
					if (timed) timedCycle(server, false); else placePlayersAndBegin(server);
				}
			}
			case PLACING -> {
				if (online(server).isEmpty()) { phase = Phase.AWAITING_PLAYERS; awaitingTicks = 0; placingTarget = null; return; }
				// Wait (on the loading screen) until everyone's whole view is loaded and generated, so the run doesn't start
				// with a burst of chunk loading and generation lag.
				boolean ready = ++placingTicks % 5 == 0;
				// and their game has finished loading in (after rejoining, the loading screen can take a while)
				if (ready) for (ServerPlayer p : online(server)) if (!chunksReady(server, p) || !p.connection.hasClientLoaded()) { ready = false; break; }
				if (ready || placingTicks > PLACING_MAX_TICKS) finishPlacing(server);
			}
			case RUNNING, ENDING -> {
				// Everyone has left this dedicated server, so reset the world and wait for someone to come back instead of
				// running the timer (and the clones) for nobody.
				if (server.isDedicatedServer() && online(server).isEmpty()) {
					if (timed) pauseTimed(server); else resetForEmptyServer(server);
					return;
				}
				if (phase == Phase.RUNNING) tickRunning(server); else tickEnding(server);
			}
			default -> {}
		}
	}

	private static void resetForEmptyServer(MinecraftServer server) {
		MocapBridge.stopAllPlayback();
		VoiceBridge.stopPlayback();
		CloneChat.clearScheduled();
		WorldLog.clearScheduled();
		CloneLids.clear();
		Recording.FRESH_ENTITIES.clear();
		clearEntityState();
		phase = Phase.RESETTING;
		save(server);
		Recur.LOGGER.info("Everyone left: resetting the world and waiting for players");
		WorldReset.begin(server, List.of(), () -> { phase = Phase.AWAITING_PLAYERS; awaitingTicks = 0; });
	}

	private static void tickRunning(MinecraftServer server) {
		{
				List<ServerPlayer> players = online(server);
				runTicks++;
				long now = timelineStart + runTicks;
				WorldLog.flushPending(now);
				if (!players.isEmpty()) {
					ServerPlayer source = players.get(0);
					PENDING.removeIf(p -> {
						if (runTicks < p.atRunTick) return false;
						CloneChat.schedule(p.past.chat, p.display, now);
						announceClone(server, p.past, MocapBridge.playClone(server, source, p.past.recording, p.past.ownerName, p.display, p.past.dimension, 0));
						WorldLog.schedule(server, p.past.edits, now);
						return true;
					});
				}
				if (!DIM_CHANGES.isEmpty() && !players.isEmpty()) changeDimensions(server, players.get(0), now);
				if (!DUE_KILLS.isEmpty()) replayKills(server, now);
				WorldLog.apply(server, now);
				CloneChat.tick(server, now);
				if (timelineStart + runTicks >= limit()) {
						if (timed) { timedCycle(server, true); return; }
						for (ServerPlayer p : players) p.kill(p.level());
				}
				if (runTicks % 20 == 0) syncAll(server);
		}
	}

	private static void tickEnding(MinecraftServer server) {
		boolean ready = true;
		for (ServerPlayer p : online(server)) {
			if (!p.isAlive() && MocapBridge.isStillRecording(p)) ready = false;
		}
		if (ready || ++endingTicks > ENDING_WAIT_TICKS) endRun(server);
	}

	/** Saves everyone's recording as a clone, respawns the dead, then resets the world. */
	private static void endRun(MinecraftServer server) {
		for (ServerPlayer p : online(server)) saveClone(server, p);
		MocapBridge.stopAllPlayback();
		VoiceBridge.stopPlayback();
		CloneChat.clearScheduled();
		WorldLog.clearScheduled();
		CloneLids.clear();
		Recording.FRESH_ENTITIES.clear(); // the world resets, so everything created this run is gone
		clearEntityState();

		List<ServerPlayer> alive = new ArrayList<>();
		for (ServerPlayer p : online(server)) {
			if (p.isAlive()) { alive.add(p); continue; }
			ServerPlayer fresh = server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
			// Dying marks the connection "waiting for respawn" (invulnerable, can't pick up or interact). Vanilla only
			// clears that when the Respawn button is pressed, so clear it here since we respawn the player ourselves.
			((dev.recur.mixin.ConnectionAccessor) fresh.connection).recur$restartClientLoadTimerAfterRespawn();
			alive.add(fresh);
		}
		LIVE_KILLS.clear(); // These belonged to players whose recording couldn't be kept.
		phase = Phase.RESETTING;
		save(server);
		syncAll(server); // so the loading screen shows the new clone count
		WorldReset.begin(server, alive, () -> placePlayersAndBegin(server));
	}

	/** Stops this player's recording and keeps it (movement via Mocap, world changes via WorldLog) as a clone. */
	private static void saveClone(MinecraftServer server, ServerPlayer p) {
		List<WorldLog.Edit> edits = WorldLog.finish(p, timelineStart + runTicks);
		String file = "lw" + sessionId + "_" + (recordingCounter++);
		String dim = RUN_START_DIMENSION.getOrDefault(p.getUUID(), "minecraft:overworld");
		if (!MocapBridge.finishAndSave(server, p, file)) { VoiceBridge.discard(p.getUUID()); CloneChat.discard(p.getUUID()); return; }
		VoiceBridge.finish(server, p.getUUID(), file);
		{
			CLONES.add(new Clone(file, p.getUUID(), NAMES.get(p.getUUID()), RECORDING_START.getOrDefault(p.getUUID(), timelineStart), dim, edits,
				CloneChat.finish(p.getUUID()), timelineStart + runTicks, false));
		}
		// The clones this player killed die at this clone's hands from now on.
		for (LiveKill k : LIVE_KILLS) if (k.killer.equals(p.getUUID())) KILLS.add(new Kill(file, k.victim, k.tick));
		LIVE_KILLS.removeIf(k -> k.killer.equals(p.getUUID()));
	}

	/** "Name #N", the name shown in the world: N counts that player's clones from the first run. */
	private static String displayName(Clone clone) {
		int n = 0;
		for (Clone c : CLONES) {
			if (c.owner.equals(clone.owner)) n++;
			if (c == clone) break;
		}
		return clone.ownerName + " #" + n;
	}

	/**
	 * "Name_N", the clone's actual player name, which commands use: no space or #, so it can be typed without quotes.
	 * Player names are limited to 16 characters.
	 */
	static String commandName(String display) {
		int hash = display.lastIndexOf(" #");
		if (hash < 0) return display.length() > 16 ? display.substring(0, 16) : display;
		String base = display.substring(0, hash), suffix = "_" + display.substring(hash + 2);
		if (base.length() + suffix.length() > 16) base = base.substring(0, Math.max(1, 16 - suffix.length()));
		return base + suffix;
	}

	/** After the world is back to pristine: put every player into the agreed starting state and start the next run. */
	private static Snapshot snapshotFor(Checkpoint target, ServerPlayer player) {
		Snapshot snap = target != null ? target.snapshots.get(player.getUUID()) : null;
		if (snap == null) snap = ORIGIN.get(player.getUUID());
		if (snap == null) { snap = Snapshot.capture(player); ORIGIN.put(player.getUUID(), snap); }
		return snap;
	}

	/** Step 1: move everyone (as a spectator) to their start spot and wait until the chunks there are loaded and ticking. */
	private static void placePlayersAndBegin(MinecraftServer server) {
		if (online(server).isEmpty()) { phase = Phase.AWAITING_PLAYERS; awaitingTicks = 0; return; }
		placingTarget = effectiveCheckpoint(server);
		timelineStart = placingTarget != null ? placingTarget.timelineTick : 0;
		for (ServerPlayer player : online(server)) {
			Snapshot snap = snapshotFor(placingTarget, player);
			LoopNetworking.sendReset(player, true); // When a saved loop resumes, the loading screen shows while players are placed.
			player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
			player.teleportTo(snap.level(server), snap.x, snap.y, snap.z, Set.of(), snap.yRot, snap.xRot, true);
		}
		placingTicks = 0;
		phase = Phase.PLACING;
	}

	/** Every chunk in a circle of the player's view distance is loaded (generated if it's new), and they're in a ticking chunk. */
	private static boolean chunksReady(MinecraftServer server, ServerPlayer p) {
		ServerLevel level = p.level();
		if (!level.isPositionEntityTicking(p.blockPosition())) return false;
		int view = server.getPlayerList().getViewDistance();
		if (p.requestedViewDistance() > 0) view = Math.min(view, p.requestedViewDistance());
		int r = Math.max(2, view - 1); // The outermost ring of chunks is only partly loaded on purpose.
		var cp = p.chunkPosition();
		var chunks = level.getChunkSource();
		int total = 0, loaded = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				total++;
				if (chunks.getChunkNow(cp.x() + dx, cp.z() + dz) != null) loaded++;
			}
		}
		// Enough of it is there (/loop loadpercent, 50% by default); the rest keeps loading once the run has started.
		return loaded * 100 >= ServerSettings.loadPercent * total;
	}

	/** Step 2: the terrain is there, so paste the saved blocks, restore the full player state and start the run. */
	private static void finishPlacing(MinecraftServer server) {
		Checkpoint target = placingTarget;
		placingTarget = null;
		for (ServerPlayer player : online(server)) {
			Snapshot snap = snapshotFor(target, player);
			snap.restoreBlocks(snap.level(server));
		}
		// A raid going on then goes on again, before its raiders come back (they rejoin it as they load).
		if (target != null) StateRebind.restoreRaids(server, target.raids);
		// Every mob, item, boat... back where it was at the checkpoint (before the clones take theirs over). After a reset in
		// place every entity was removed, so the ones from the loop start come back too.
		EntityRestore.begin(server, WorldReset.lastWasInPlace() ? EntityRestore.withBase(TimedReset.firstSeenEntities(), target) : target);
		previousEffective = target != null ? target.id : -1;
		runTarget = target;
		if (target != null && !target.clocks.isEmpty()) {
			WorldReset.applyClocks(server, target.clocks);
			StateRebind.restoreWeather(server, target.weather);
		} else {
			WorldReset.applyClocks(server, WorldReset.clockSnapshot());
			StateRebind.restoreWeather(server, WorldReset.weatherSnapshot());
		}
		ContainerDeltas.setCheckpoint(target);
		// Players stay on the loading screen until everything before the checkpoint has been replayed and has settled
		// (water that put out lava, sand that fell...). Only then do they get control, as if they'd got there normally.
		beginRun(server, () -> {
			if (target != null) WorldSettle.run(server);
			for (ServerPlayer player : online(server)) {
				Snapshot snap = snapshotFor(target, player);
				snap.restorePlayer(player);
				if (target != null) snap.restoreVehicle(player);
				LoopNetworking.sendReset(player, false);
			}
			if (cancelAfterReset != null) {
				// it stops once the entities the reset puts back are all back (stopping drops the ones still on their way)
				stopWho = cancelAfterReset;
				stopWhere = target != null ? "\"" + target.name + "\"" : "the start of the loop";
				stopWaited = 0;
			}
		});
		// All of that can take a while; don't let the server rush through the ticks it "missed" meanwhile (the world
		// would race ahead for a moment right as everyone gets control).
		((dev.recur.mixin.MinecraftServerAccessor) server).recur$setNextTickTimeNanos(net.minecraft.util.Util.getNanos());
	}

	/** Entity ids of the clones playing right now (so clients can fade them in, or hide them). */
	private static final List<Integer> CLONE_ENTITY_IDS = new ArrayList<>();
	/** Which past run each playing clone entity is. */
	private static final Map<Integer, Clone> CLONE_BY_ENTITY = new HashMap<>();

	/** The player a playing clone is a recording of, or null. */
	public static UUID ownerOf(net.minecraft.world.entity.Entity clone) {
		Clone c = CLONE_BY_ENTITY.get(clone.getId());
		return c != null ? c.owner : null;
	}

	private static void announceClone(MinecraftServer server, Clone clone, int entityId) { announceClone(server, clone, entityId, true); }

	private static void announceClone(MinecraftServer server, Clone clone, int entityId, boolean fadeIn) {
		if (entityId < 0) return;
		CLONE_ENTITY_IDS.add(entityId);
		CLONE_BY_ENTITY.put(entityId, clone);
		long now = timelineStart + runTicks;
		for (WorldLog.Edit e : clone.edits)
			if (e.kind() == 6 && e.tick() > now) DIM_CHANGES.add(new DimChange(e.tick(), clone, e.data().getStringOr("to", clone.dimension)));
		// Look in every dimension, because after a portal the clone is no longer in the one its run started in.
		net.minecraft.world.entity.Entity cloneEntity = null;
		for (ServerLevel l : server.getAllLevels()) {
			cloneEntity = l.getEntity(entityId);
			if (cloneEntity != null) break;
		}
		if (cloneEntity != null) VoiceBridge.play(server, clone.recording, cloneEntity);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) LoopNetworking.sendClones(p, List.of(entityId), ages(List.of(entityId)), fadeIn);
	}

	/** For each clone entity, the order its clone was made in (its place in CLONES), or -1. */
	private static List<Integer> ages(List<Integer> entityIds) {
		List<Integer> ages = new ArrayList<>(entityIds.size());
		for (int id : entityIds) {
			Clone clone = CLONE_BY_ENTITY.get(id);
			ages.add(clone != null ? CLONES.indexOf(clone) : -1);
		}
		return ages;
	}

	private static void startRecording(MinecraftServer server, ServerPlayer p) {
		MocapBridge.startRecording(server, p);
		VoiceBridge.startRecording(p.getUUID());
		CloneChat.startRecording(p.getUUID());
		WorldLog.startRecording(p.getUUID());
	}

	private static void beginRun(MinecraftServer server) { beginRun(server, null); }

	/**
	 * Every sound still playing from before the reset (a phantom's screech, a mob's long death sound...) stops for everyone.
	 * Music, menu sounds and voices keep going.
	 */
	private static void stopSounds(MinecraftServer server) {
		for (net.minecraft.sounds.SoundSource source : net.minecraft.sounds.SoundSource.values()) {
			if (source == net.minecraft.sounds.SoundSource.MUSIC || source == net.minecraft.sounds.SoundSource.UI
				|| source == net.minecraft.sounds.SoundSource.VOICE) continue;
			var packet = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(null, source);
			for (ServerPlayer p : server.getPlayerList().getPlayers())
				if (!(p instanceof com.mt1006.mocap.utils.FakePlayer)) p.connection.send(packet);
		}
	}

	/**
	 * Every player gets the chunks around them again, as they are after the reset. A ghost block (one the player's game
	 * thinks it placed, but the server never did) only lives in their game, so the reset never touched it, and a reset
	 * that doesn't reload chunks would otherwise leave it there.
	 */
	private static void resendChunks(MinecraftServer server) {
		for (ServerPlayer p : online(server)) {
			ServerLevel level = p.level();
			// and what they hold, which a reset can leave the game and the server disagreeing about (a bow that won't draw)
			p.stopUsingItem();
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
			p.inventoryMenu.sendAllDataToRemote();
			p.getChunkTrackingView().forEach(pos -> {
				net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
				if (chunk != null) p.connection.chunkSender.markChunkPendingToSend(chunk);
			});
		}
	}

	/** beforeRecording: runs after the clones and past world changes are in place, right before recording starts. */
	private static void beginRun(MinecraftServer server, Runnable beforeRecording) {
		stopSounds(server);
		VoiceBridge.onTick(timelineStart, true);
		WorldLog.clearScheduled();
		CloneChat.clearScheduled();
		CLONE_ENTITY_IDS.clear();
		CLONE_BY_ENTITY.clear();
		DIM_CHANGES.clear();
		LIVE_KILLS.clear();
		DUE_KILLS.clear();
		DIED_BY_REPLAY.clear();
		for (Kill k : KILLS) if (k.tick > timelineStart) DUE_KILLS.add(k);
		DUE_KILLS.sort(java.util.Comparator.comparingLong(Kill::tick));
		runTicks = 0;
		runCounter++;
		PENDING.clear();
		phase = Phase.RUNNING;
		List<ServerPlayer> players = online(server);

		IN_RUN.clear();
		for (ServerPlayer p : players) {
			IN_RUN.add(p.getUUID());
			RUN_START_DIMENSION.put(p.getUUID(), p.level().dimension().identifier().toString());
			RECORDING_START.put(p.getUUID(), timelineStart);
		}

		if (!players.isEmpty()) {
			ServerPlayer source = players.get(0);
			for (Clone clone : CLONES) {
				if (clone.ghost) {
					// A removed clone still takes what it took (from containers, item frames and mobs it kills), and does nothing else.
					if (TimedReset.antiDupe()) WorldLog.schedule(server, WorldLog.ghostEdits(clone.edits, Long.MIN_VALUE), timelineStart);
					continue;
				}
				long offset = timelineStart - clone.startTick;
				String display = displayName(clone);
				long killedAt = killedBefore(clone.recording, timelineStart + 1, 0);
				if (killedAt != Long.MAX_VALUE) {
					// It was killed before this run starts, so only what it did until then happens.
					List<WorldLog.Edit> until = new ArrayList<>();
					for (WorldLog.Edit e : clone.edits) if (e.tick() < killedAt) until.add(e);
					WorldLog.schedule(server, until, timelineStart);
					if (TimedReset.antiDupe()) WorldLog.schedule(server, WorldLog.ghostEdits(clone.edits, killedAt), timelineStart);
				} else if (clone.endTick >= 0 && clone.endTick <= timelineStart) {
					WorldLog.schedule(server, clone.edits, timelineStart);
				} else if (offset >= 0) {
					CloneChat.schedule(clone.chat, display, timelineStart);
					announceClone(server, clone, MocapBridge.playClone(server, source, clone.recording, clone.ownerName, display,
						clone.dimensionAt(timelineStart), (int) offset));
					WorldLog.schedule(server, clone.edits, timelineStart);
				} else {
					PENDING.add(new PendingClone(clone, (int) -offset, display));
				}
			}
		}
		if (beforeRecording != null) beforeRecording.run();
		if (phase == Phase.IDLE) return; // the loop was cancelled right there
		resendChunks(server);
		for (ServerPlayer p : players) startRecording(server, p);

		syncAll(server);
	}

	/**
	 * A player crouched and hit a clone: it dies right now (its moves, voice, chat and the rest of its world changes stop).
	 * In later runs it plays until this moment and dies here again. The exception is when the player's own clone, which
	 * is its killer by then, has died before this moment.
	 */
	public static boolean killClone(ServerPlayer by, net.minecraft.world.entity.Entity cloneEntity) {
		if (phase != Phase.RUNNING || !PARTICIPANTS.contains(by.getUUID())) return false;
		Clone clone = CLONE_BY_ENTITY.get(cloneEntity.getId());
		if (clone == null) return false;
		MinecraftServer server = by.level().getServer();
		long now = timelineStart + runTicks;
		dropLoot(cloneEntity, clone, by);
		cloneDies(server, cloneEntity.getId(), clone, now);
		if (MocapBridge.isStillRecording(by)) LIVE_KILLS.add(new LiveKill(by.getUUID(), clone.recording, now));
		broadcast(server, Component.literal(by.getGameProfile().name() + " killed " + displayName(clone) + "."));
		syncAll(server);
		return true;
	}

	/**
	 * You killed a clone: everything it carried falls out, with its experience, like a player dying (unless that would
	 * duplicate items: see ServerSettings). They're in your recording, so when your clone kills it again in later runs
	 * the same items and experience fly out, and your clone picks up what you did (see diedByReplay).
	 */
	private static void dropLoot(net.minecraft.world.entity.Entity cloneEntity, Clone clone, ServerPlayer killer) {
		if (!ServerSettings.cloneLoot || !(cloneEntity.level() instanceof ServerLevel level)) return;
		if (TimedReset.antiDupe()) { // never with the anti-dupe rules on: say so, or it looks broken
			if (killer != null) killer.sendSystemMessage(Component.literal("Clones drop nothing while the anti-dupe rules are on ("
				+ (TimedReset.isActive() ? "Timed" : "Loop") + " anti-dupe in the mod menu)."), true);
			return;
		}
		var random = level.getRandom();
		List<net.minecraft.world.item.ItemStack> carried = MocapBridge.carried(cloneEntity);
		int xp = Math.min(MocapBridge.experienceLevel(cloneEntity) * 7, 100);
		MobDrops.startReward("c|" + clone.recording);
		try {
			for (net.minecraft.world.item.ItemStack stack : carried) {
				var item = new net.minecraft.world.entity.item.ItemEntity(level, cloneEntity.getX(), cloneEntity.getEyeY() - 0.3, cloneEntity.getZ(), stack);
				item.setDefaultPickUpDelay();
				float speed = random.nextFloat() * 0.5f, angle = random.nextFloat() * ((float) Math.PI * 2f);
				item.setDeltaMovement(-Math.sin(angle) * speed, 0.2, Math.cos(angle) * speed);
				level.addFreshEntity(item);
			}
			// what a player drops when they die: 7 per level, at most 100
			if (xp > 0) net.minecraft.world.entity.ExperienceOrb.award(level, cloneEntity.position(), xp);
		} finally {
			MobDrops.endReward();
		}
	}

	/**
	 * A clone died the way its player did (its recording ends there): with "Killed clones drop items" on, everything it
	 * carried falls out too, with its experience. Its player never really died then (a death ends the run), so this
	 * isn't in any recording: it drops anew every time.
	 */
	public static void onCloneDied(net.minecraft.world.entity.Entity cloneEntity) {
		Clone clone = CLONE_BY_ENTITY.get(cloneEntity.getId());
		if (clone == null) return;
		int depth = ReplayScope.depth;
		ReplayScope.depth = 0; // these really drop (they aren't items from a recording)
		try { dropLoot(cloneEntity, clone, null); }
		finally { ReplayScope.depth = depth; }
	}

	/** Clones a replayed kill killed this run (by recording): what they dropped comes from their killer's recording. */
	private static final Set<String> DIED_BY_REPLAY = new java.util.HashSet<>();

	public static boolean diedByReplay(String recording) { return DIED_BY_REPLAY.contains(recording); }

	/**
	 * /loop undo: the newest clones (of this player, or anyone's when owner is null) are gone for good, as if those runs
	 * never happened: they stop playing now, and from the next run on nothing they did happens. Later clones of that player
	 * get their numbers back. Returns how many were removed.
	 */
	public static int undo(MinecraftServer server, UUID owner, int count) {
		if (phase == Phase.IDLE) return 0;
		int removed = 0;
		for (int i = CLONES.size() - 1; i >= 0 && removed < count; i--) {
			Clone c = CLONES.get(i);
			if (owner != null && !c.owner.equals(owner)) continue;
			CLONES.remove(i);
			removed++;
			KILLS.removeIf(k -> k.killer.equals(c.recording) || k.victim.equals(c.recording));
			DUE_KILLS.removeIf(k -> k.killer.equals(c.recording) || k.victim.equals(c.recording));
			PENDING.removeIf(p -> p.past == c);
			DIM_CHANGES.removeIf(d -> d.past == c);
			WorldLog.unschedule(c.edits);
			CloneChat.unschedule(displayName(c));
			for (var e : new ArrayList<>(CLONE_BY_ENTITY.entrySet())) {
				if (e.getValue() != c) continue;
				CLONE_BY_ENTITY.remove(e.getKey());
				CLONE_ENTITY_IDS.remove((Integer) e.getKey());
				for (ServerLevel l : server.getAllLevels()) {
					net.minecraft.world.entity.Entity entity = l.getEntity(e.getKey());
					if (entity != null) MocapBridge.stopFor(entity);
				}
			}
		}
		if (removed > 0) { save(server); syncAll(server); }
		return removed;
	}

	/** Kills a playing clone. It plays the death animation and nothing more of its run happens. */
	private static void cloneDies(MinecraftServer server, int entityId, Clone clone, long now) {
		CLONE_BY_ENTITY.remove(entityId);
		net.minecraft.world.entity.Entity entity = null;
		for (ServerLevel l : server.getAllLevels()) if ((entity = l.getEntity(entityId)) != null) break;
		if (entity != null) MocapBridge.killFor(entity);
		DIM_CHANGES.removeIf(d -> d.past == clone);
		PENDING.removeIf(p -> p.past == clone);
		WorldLog.unschedule(clone.edits);
		CloneChat.unschedule(displayName(clone));
		// In timed resets you keep your stuff, so what its player took after this moment mustn't become takeable again.
		if (TimedReset.antiDupe()) WorldLog.schedule(server, WorldLog.ghostEdits(clone.edits, now), now);
	}

	/** Is the clone of this recording playing (alive) right now? */
	private static boolean playing(String recording) {
		for (Clone c : CLONE_BY_ENTITY.values()) if (c.recording.equals(recording)) return true;
		return false;
	}

	/** Carries out kills from earlier runs that are due now. The victim dies if both it and its killer are still alive. */
	private static void replayKills(MinecraftServer server, long now) {
		while (!DUE_KILLS.isEmpty() && DUE_KILLS.get(0).tick <= now) {
			Kill k = DUE_KILLS.remove(0);
			if (!playing(k.killer)) continue; // its killer died first: it lives on
			for (var e : new ArrayList<>(CLONE_BY_ENTITY.entrySet())) {
				if (!e.getValue().recording.equals(k.victim)) continue;
				DIED_BY_REPLAY.add(k.victim); // its items and experience come out of the killer's recording
				cloneDies(server, e.getKey(), e.getValue(), now);
			}
		}
	}

	/**
	 * When the clone of this recording gets killed before `before` (Long.MAX_VALUE if it doesn't): the first kill of it
	 * whose killer is itself still alive then (killers can be killed too, so this looks back through those).
	 */
	private static long killedBefore(String recording, long before, int depth) {
		if (depth > 64) return Long.MAX_VALUE;
		long first = Long.MAX_VALUE;
		for (Kill k : KILLS) {
			if (!k.victim.equals(recording) || k.tick >= before || k.tick >= first) continue;
			Clone killer = null;
			for (Clone c : CLONES) if (c.recording.equals(k.killer)) killer = c;
			if (killer == null || killer.ghost || killer.startTick > k.tick) continue;
			if (killedBefore(k.killer, k.tick, depth + 1) == Long.MAX_VALUE) first = k.tick;
		}
		return first;
	}

	/** Every client gets the player info of every clone again (a client only draws a player it has info for). */
	private static void refreshCloneInfo(MinecraftServer server) {
		List<ServerPlayer> fakes = new ArrayList<>();
		for (ServerLevel l : server.getAllLevels())
			for (ServerPlayer fp : l.players()) if (fp instanceof com.mt1006.mocap.utils.FakePlayer && !fp.isRemoved()) fakes.add(fp);
		if (fakes.isEmpty()) return;
		var packet = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(fakes);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p instanceof com.mt1006.mocap.utils.FakePlayer) continue;
			p.connection.send(packet);
			if (!CLONE_ENTITY_IDS.isEmpty()) LoopNetworking.sendClones(p, new ArrayList<>(CLONE_ENTITY_IDS), ages(CLONE_ENTITY_IDS), false);
		}
	}

	/**
	 * Leaving the End (exit portal / credits) gives the player a brand-new entity: their recording goes on with it, and
	 * their clone goes back to the overworld at this moment (like through any portal).
	 */
	public static void onEndExit(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		if (phase == Phase.IDLE || !PARTICIPANTS.contains(newPlayer.getUUID())) return;
		com.mt1006.mocap.mocap.recording.Recording.rebind(oldPlayer, newPlayer);
		if (phase == Phase.RUNNING && MocapBridge.isStillRecording(newPlayer))
			WorldLog.addDimensionChange(newPlayer, timelineStart + runTicks, newPlayer.level().dimension().identifier().toString());
	}

	/** A recording player went through a portal, so their clone will vanish and come out on the other side at this moment. */
	public static void onChangeDimension(ServerPlayer player) {
		if (phase != Phase.RUNNING || !PARTICIPANTS.contains(player.getUUID()) || !MocapBridge.isStillRecording(player)) return;
		WorldLog.addDimensionChange(player, timelineStart + runTicks, player.level().dimension().identifier().toString());
	}

	/** Clones whose players went through a portal right now: gone from here, and playing on in the other dimension. */
	private static void changeDimensions(MinecraftServer server, ServerPlayer source, long now) {
		List<DimChange> due = new ArrayList<>();
		for (DimChange d : DIM_CHANGES) if (d.tick <= now) due.add(d);
		if (due.isEmpty()) return;
		DIM_CHANGES.removeAll(due);
		for (DimChange d : due) {
			Clone clone = d.past;
			Integer oldId = null;
			for (var e : CLONE_BY_ENTITY.entrySet()) if (e.getValue() == clone) oldId = e.getKey();
			if (oldId != null) {
				for (ServerLevel l : server.getAllLevels()) {
					net.minecraft.world.entity.Entity old = l.getEntity(oldId);
					if (old != null) { MocapBridge.stopFor(old); if (!old.isRemoved()) old.discard(); }
				}
				CLONE_BY_ENTITY.remove(oldId);
				CLONE_ENTITY_IDS.remove(oldId);
			}
			DIM_CHANGES.removeIf(x -> x.past == clone);
			int id = MocapBridge.playClone(server, source, clone.recording, clone.ownerName, displayName(clone), d.dim, (int) (now - clone.startTick));
			announceClone(server, clone, id, false); // It walks out of the portal, so it appears without fading in.
		}
	}

	// checkpoints and voting

	/** The checkpoint the next run will use. There is one only when every online player votes for the same one. */
	private static Checkpoint effectiveCheckpoint(MinecraftServer server) {
		List<ServerPlayer> players = online(server);
		if (players.isEmpty()) return null;
		int id = VOTES.getOrDefault(players.get(0).getUUID(), -1);
		if (id < 0) return null;
		for (ServerPlayer p : players) if (VOTES.getOrDefault(p.getUUID(), -1) != id) return null;
		return findCheckpoint(id);
	}

	private static Checkpoint findCheckpoint(int id) {
		for (Checkpoint c : CHECKPOINTS) if (c.id == id) return c;
		return null;
	}

	private static int votesFor(MinecraftServer server, int id) {
		int n = 0;
		for (ServerPlayer p : online(server)) if (VOTES.getOrDefault(p.getUUID(), -1) == id) n++;
		return n;
	}

	private static int effectiveId(MinecraftServer server) {
		Checkpoint c = effectiveCheckpoint(server);
		return c != null ? c.id : -1;
	}

	/** Called for create / vote / delete requests from the client UI. */
	public static void onAction(ServerPlayer player, int action, int id) {
		if (action == LoopNetworking.ACTION_START || action == LoopNetworking.ACTION_START_TIMED) {
			MinecraftServer server = player.level().getServer();
			if (phase != Phase.IDLE || !WorldGuard.allowed() || TimedReset.isActive()) return;
			boolean allowed = server.isSingleplayerOwner(player.nameAndId())
				|| net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
			if (!allowed) { player.sendOverlayMessage(Component.literal("Only operators can start this.")); return; }
			if (action == LoopNetworking.ACTION_START_TIMED) startTimed(server, player);
			else {
				// The length chosen in the pause menu: minutes, or -1 for no time limit (0 keeps the current one).
				if (id > 0) timeoutTicks = Math.min(id, 24 * 60) * 60 * 20;
				else if (id < 0) timeoutTicks = NO_TIME_LIMIT;
				start(server, player);
			}
			return;
		}
		if (action == LoopNetworking.ACTION_CANCEL) {
			// Cancelling: every clone goes and the world stays as it is now (like /loop stop).
			MinecraftServer server = player.level().getServer();
			if (phase == Phase.IDLE || !ServerSettings.canEdit(player)) return;
			if (id == 1 && !timed) {
				// ...or first back to the selected checkpoint: the run ends and resets like after a death, and the loop
				// stops right as everyone would get control (see finishPlacing).
				cancelAfterReset = player.getGameProfile().name();
				if (phase == Phase.RUNNING) {
					phase = Phase.ENDING;
					endingTicks = 0;
				}
				broadcast(server, Component.literal(cancelAfterReset + " is cancelling the loop. The world resets one last time first."));
				return;
			}
			stop(server);
			broadcast(server, Component.literal(player.getGameProfile().name() + " cancelled the loop. The clones are gone and the world stays as it is."));
			return;
		}
		if (phase == Phase.IDLE || !PARTICIPANTS.contains(player.getUUID())) return;
		if (timed) return; // no checkpoints in timed resets
		MinecraftServer server = player.level().getServer();
		String who = player.getGameProfile().name();

		switch (action) {
			case LoopNetworking.ACTION_CREATE -> { createCheckpoint(player, null); return; }
			case LoopNetworking.ACTION_VOTE -> {
				if (id != -1 && findCheckpoint(id) == null) return;
				if (VOTES.getOrDefault(player.getUUID(), -1) == id) return;
				VOTES.put(player.getUUID(), id);
				announceVote(server, who, id);
			}
			case LoopNetworking.ACTION_DELETE -> {
				Checkpoint gone = findCheckpoint(id);
				if (gone == null) return;
				CHECKPOINTS.removeIf(c -> c.id == id);
				AGREED_HISTORY.removeIf(a -> a == id);
				// Everyone who picked it (or was being asked about it) goes back to the last checkpoint everyone agreed on.
				int fallback = lastAgreed();
				boolean moved = VOTES.containsValue(id);
				VOTES.replaceAll((k, v) -> v == id ? fallback : v);
				broadcast(server, Component.literal(who + " deleted " + gone.name + "."
					+ (moved ? " Its votes went back to " + (fallback >= 0 ? nameOf(fallback) : "the loop start") + "." : "")));
				previousEffective = effectiveId(server);
				noteAgreement(server);
			}
			case LoopNetworking.ACTION_STAR -> {
				Checkpoint cp = findCheckpoint(id);
				if (cp == null) return;
				if (cp.star > 0) {
					cp.star = 0;
				} else {
					int top = 0;
					for (Checkpoint c : CHECKPOINTS) top = Math.max(top, c.star);
					cp.star = top + 1;
				}
				renumberStars();
			}
			default -> {}
		}
		save(server);
		syncAll(server);
	}

	/** Creates a checkpoint of everyone, and the creator immediately votes for it. */
	public static void createCheckpoint(ServerPlayer player, byte[] thumbnail) {
		if (phase != Phase.RUNNING || timed || !PARTICIPANTS.contains(player.getUUID())) return;
		MinecraftServer server = player.level().getServer();
		String who = player.getGameProfile().name();
		Map<UUID, Snapshot> snaps = new LinkedHashMap<>();
		for (ServerPlayer p : online(server)) snaps.put(p.getUUID(), Snapshot.capture(p));
		int n = ++checkpointCounter;
		Checkpoint cp = new Checkpoint(n, "Checkpoint " + n, who, timelineStart + runTicks, snaps);
		if (thumbnail != null && thumbnail.length == Checkpoint.THUMB_W * Checkpoint.THUMB_H * 3) cp.thumbnail = thumbnail;
		cp.clocks = WorldReset.captureClocks(server);
		cp.weather = StateRebind.captureWeather(server);
		cp.raids = StateRebind.captureRaids(server);
		Set<UUID> alive = new java.util.HashSet<>();
		cp.entities = EntityRestore.capture(server, alive);
		cp.gone = EntityRestore.goneExcept(alive);
		CHECKPOINTS.add(cp);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) LoopNetworking.sendThumb(p, n, cp.thumbnail);
		VOTES.put(player.getUUID(), n);
		announceVote(server, who, n);
		// The creator names it first; then everyone else is asked whether to switch to it.
		if (LoopNetworking.hasRecur(player)) {
			cp.awaitingName = true;
			LoopNetworking.sendPrompt(player, LoopNetworking.PROMPT_NAME, n, cp.name, who);
		} else {
			offerToEveryone(server, cp, player);
		}
		save(server);
		syncAll(server);
	}

	/** Asks every other player whether to switch to this new checkpoint. */
	private static void offerToEveryone(MinecraftServer server, Checkpoint cp, ServerPlayer creator) {
		cp.awaitingName = false;
		broadcast(server, Component.literal(cp.creator + " created " + cp.name + "."));
		for (ServerPlayer p : online(server)) {
			if (p == creator || VOTES.getOrDefault(p.getUUID(), -1) == cp.id) continue;
			LoopNetworking.sendPrompt(p, LoopNetworking.PROMPT_ACCEPT, cp.id, cp.name, cp.creator);
		}
	}

	/** The latest checkpoint everyone agreed on that still exists (-1 = the loop start). */
	private static int lastAgreed() {
		for (int i = AGREED_HISTORY.size() - 1; i >= 0; i--) {
			int id = AGREED_HISTORY.get(i);
			if (id == -1 || findCheckpoint(id) != null) return id;
		}
		return -1;
	}

	/** Remembers what everyone agrees on right now (if they all agree on something). */
	private static void noteAgreement(MinecraftServer server) {
		List<ServerPlayer> players = online(server);
		if (players.isEmpty()) return;
		int agreed;
		if (votesFor(server, -1) == players.size()) agreed = -1;
		else {
			agreed = effectiveId(server);
			if (agreed < 0) return; // no agreement right now
		}
		if (!AGREED_HISTORY.isEmpty() && AGREED_HISTORY.get(AGREED_HISTORY.size() - 1) == agreed) return;
		AGREED_HISTORY.remove(Integer.valueOf(agreed));
		AGREED_HISTORY.add(agreed);
	}

	/** Starred checkpoints get positions 1, 2, 3... in their current order. */
	private static void renumberStars() {
		List<Checkpoint> starred = new ArrayList<>();
		for (Checkpoint c : CHECKPOINTS) if (c.star > 0) starred.add(c);
		starred.sort(java.util.Comparator.comparingInt(c -> c.star));
		for (int i = 0; i < starred.size(); i++) starred.get(i).star = i + 1;
	}

	/** A player dragged the starred checkpoints into a new order (top first). */
	public static void reorderStars(ServerPlayer player, List<Integer> ids) {
		if (phase == Phase.IDLE || !PARTICIPANTS.contains(player.getUUID())) return;
		int pos = 1;
		for (int id : ids) {
			Checkpoint c = findCheckpoint(id);
			if (c != null && c.star > 0) c.star = pos++;
		}
		// Starred ones the list didn't mention (e.g. starred by someone else meanwhile) go below.
		for (Checkpoint c : CHECKPOINTS) if (c.star > 0 && !ids.contains(c.id)) c.star = pos++;
		renumberStars();
		MinecraftServer server = player.level().getServer();
		save(server);
		syncAll(server);
	}

	public static void rename(ServerPlayer player, int id, String name) {
		if (phase == Phase.IDLE || !PARTICIPANTS.contains(player.getUUID())) return;
		Checkpoint cp = findCheckpoint(id);
		if (cp == null) return;
		name = name.strip();
		MinecraftServer server = player.level().getServer();
		if (!name.isEmpty()) cp.name = name.length() > 32 ? name.substring(0, 32) : name;
		// The creator named their new checkpoint (an empty name keeps the default): now ask everyone else.
		if (cp.awaitingName && cp.creator.equals(player.getGameProfile().name())) offerToEveryone(server, cp, player);
		save(server);
		syncAll(server);
	}

	private static String nameOf(int id) {
		Checkpoint c = findCheckpoint(id);
		return c != null ? c.name : "Checkpoint " + id;
	}

	private static void announceVote(MinecraftServer server, String who, int id) {
		int total = online(server).size();
		int effective = effectiveId(server);
		if (id >= 0) {
			int agree = votesFor(server, id);
			broadcast(server, Component.literal(who + " wants " + nameOf(id) + " (" + agree + "/" + total + " agree)."));
			if (effective == id && previousEffective != id) {
				broadcast(server, Component.literal("Everyone agrees - the next run starts at " + nameOf(id) + "."));
			}
		} else {
			broadcast(server, Component.literal(who + " wants the loop start."));
			if (previousEffective >= 0 && effective < 0) {
				broadcast(server, Component.literal(nameOf(previousEffective)
					+ " is no longer unanimous - the next run goes back to the loop start."));
			}
		}
		previousEffective = effective;
		noteAgreement(server);
	}

	// messaging and sync

	private static void broadcast(MinecraftServer server, Component message) {
		for (ServerPlayer p : online(server)) p.sendOverlayMessage(message);
	}

	public static void syncAll(MinecraftServer server) {
		for (ServerPlayer p : online(server)) sync(server, p);
	}

	private static void sync(MinecraftServer server, ServerPlayer player) {
		int total = online(server).size();
		int mine = 0;
		for (Clone c : CLONES) if (!c.ghost && c.owner.equals(player.getUUID())) mine++;

		List<LoopNetworking.CheckpointInfo> infos = new ArrayList<>();
		for (Checkpoint c : CHECKPOINTS) {
			Snapshot s = c.anchor(player.getUUID());
			infos.add(new LoopNetworking.CheckpointInfo(c.id, c.name, c.creator, c.timelineTick,
				s != null ? s.dimension : "", s != null ? (int) s.x : 0, s != null ? (int) s.y : 0, s != null ? (int) s.z : 0,
				votesFor(server, c.id), c.star));
		}
		// Starred ones first (in their order), then the rest oldest first.
		infos.sort(java.util.Comparator.comparingInt((LoopNetworking.CheckpointInfo c) -> c.star() > 0 ? c.star() : Integer.MAX_VALUE));
		LoopNetworking.sendState(player, new LoopNetworking.StatePayload(WorldGuard.allowed(), phase != Phase.IDLE, mine, liveClones(), runCounter,
			remainingTicks(), VOTES.getOrDefault(player.getUUID(), -1), effectiveId(server), total, votesFor(server, -1), infos,
			timed ? remainingTicks() : -1));
	}

	// persistence

	private static Path stateFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("recur").resolve("state.dat");
	}

	private static void deleteSaved(MinecraftServer server) {
		try { Files.deleteIfExists(stateFile(server)); } catch (IOException e) { Recur.LOGGER.warn("Could not delete loop state", e); }
	}

	public static void save(MinecraftServer server) {
		if (phase == Phase.IDLE) return;
		CompoundTag root = new CompoundTag();
		root.putString("session", sessionId);
		root.putInt("timeout", timeoutTicks);
		root.putBoolean("timed", timed);
		root.putInt("recordingCounter", recordingCounter);
		root.putInt("runCounter", runCounter);
		root.putInt("checkpointCounter", checkpointCounter);

		ListTag players = new ListTag();
		for (UUID id : PARTICIPANTS) {
			CompoundTag p = new CompoundTag();
			p.putString("uuid", id.toString());
			p.putString("name", NAMES.getOrDefault(id, "?"));
			p.putInt("vote", VOTES.getOrDefault(id, -1));
			Snapshot s = ORIGIN.get(id);
			if (s != null) p.put("origin", s.toTag());
			players.add(p);
		}
		root.put("players", players);

		ListTag cl = new ListTag();
		for (Clone c : CLONES) {
			CompoundTag t = new CompoundTag();
			t.putString("recording", c.recording); t.putString("owner", c.owner.toString()); t.putString("ownerName", c.ownerName);
			t.putLong("start", c.startTick); t.putString("dim", c.dimension);
			t.put("edits", WorldLog.toTag(c.edits));
			t.put("chat", CloneChat.toTag(c.chat));
			t.putLong("end", c.endTick);
			t.putBoolean("ghost", c.ghost);
			cl.add(t);
		}
		root.put("clones", cl);
		ListTag kl = new ListTag();
		for (Kill k : KILLS) {
			CompoundTag t = new CompoundTag();
			t.putString("killer", k.killer); t.putString("victim", k.victim); t.putLong("tick", k.tick);
			kl.add(t);
		}
		root.put("kills", kl);

		ListTag cps = new ListTag();
		for (Checkpoint c : CHECKPOINTS) cps.add(c.toTag());
		root.put("checkpoints", cps);
		if (startThumbnail != null) root.putByteArray("startThumb", startThumbnail);
		root.putIntArray("agreed", AGREED_HISTORY.stream().mapToInt(Integer::intValue).toArray());

		CompoundTag clocks = new CompoundTag();
		WorldReset.clockSnapshot().forEach(clocks::putLong);
		root.put("clocks", clocks);
		root.put("weather", WorldReset.weatherSnapshot());
		if (!timed) root.put("mined", CloneMade.toTag()); // timed resets keep it with their own state

		try {
			Path file = stateFile(server);
			Files.createDirectories(file.getParent());
			NbtIo.writeCompressed(root, file);
		} catch (IOException e) {
			Recur.LOGGER.error("Could not save loop state", e);
		}
	}

	/** Called on server start: if a loop was active when the server stopped, resume it (the world on disk is pristine). */
	public static void loadSaved(MinecraftServer server) {
		Path file = stateFile(server);
		if (!Files.exists(file)) return;
		try {
			CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			reset();
			sessionId = root.getStringOr("session", "x");
			timeoutTicks = root.getIntOr("timeout", DEFAULT_TIMEOUT_TICKS);
			timed = root.getBooleanOr("timed", false);
			recordingCounter = root.getIntOr("recordingCounter", 0);
			runCounter = root.getIntOr("runCounter", 0);
			checkpointCounter = root.getIntOr("checkpointCounter", 0);

			for (Tag t : root.getListOrEmpty("players")) {
				if (!(t instanceof CompoundTag p)) continue;
				UUID id = UUID.fromString(p.getStringOr("uuid", ""));
				PARTICIPANTS.add(id);
				NAMES.put(id, p.getStringOr("name", "?"));
				VOTES.put(id, p.getIntOr("vote", -1));
				p.getCompound("origin").ifPresent(o -> ORIGIN.put(id, Snapshot.fromTag(o)));
			}
			for (Tag t : root.getListOrEmpty("clones")) {
				if (!(t instanceof CompoundTag c)) continue;
				CLONES.add(new Clone(c.getStringOr("recording", ""), UUID.fromString(c.getStringOr("owner", "")),
					c.getStringOr("ownerName", "?"), c.getLongOr("start", 0), c.getStringOr("dim", "minecraft:overworld"),
					WorldLog.fromTag(c.getListOrEmpty("edits")), CloneChat.fromTag(c.getListOrEmpty("chat")), c.getLongOr("end", -1),
					c.getBooleanOr("ghost", false)));
			}
			for (Tag t : root.getListOrEmpty("kills"))
				if (t instanceof CompoundTag k) KILLS.add(new Kill(k.getStringOr("killer", ""), k.getStringOr("victim", ""), k.getLongOr("tick", 0)));
			for (Tag t : root.getListOrEmpty("checkpoints")) if (t instanceof CompoundTag c) CHECKPOINTS.add(Checkpoint.fromTag(c));
			startThumbnail = root.getByteArray("startThumb").orElse(null);
			for (int a : root.getIntArray("agreed").orElse(new int[0])) AGREED_HISTORY.add(a);

			Map<String, Long> clocks = new HashMap<>();
			CompoundTag ct = root.getCompoundOrEmpty("clocks");
			for (String key : ct.keySet()) clocks.put(key, ct.getLongOr(key, 0));
			WorldReset.setClockSnapshot(clocks);
			WorldReset.setWeatherSnapshot(root.getCompoundOrEmpty("weather"));
			if (!timed) CloneMade.fromTag(root.getCompoundOrEmpty("mined"));

			LoopOverlay.active = true;
			TimedReset.beginTracking(server);
			awaitingTicks = 0;
			phase = Phase.AWAITING_PLAYERS;
			Recur.LOGGER.info("Resumed loop: {} clones, {} checkpoints, waiting for players", CLONES.size(), CHECKPOINTS.size());
		} catch (Exception e) {
			Recur.LOGGER.error("Could not load loop state", e);
		}
	}
}
