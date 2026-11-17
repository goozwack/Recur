package com.mt1006.mocap.mocap.playing;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import com.mt1006.mocap.command.CommandInfo;
import com.mt1006.mocap.events.PlayerConnectionEvent;
import com.mt1006.mocap.mocap.actions.Action;
import com.mt1006.mocap.mocap.recording.EntityState;
import com.mt1006.mocap.mocap.recording.Recording;
import com.mt1006.mocap.mocap.settings.Settings;
import com.mt1006.mocap.network.MocapPacketS2C;
import com.mt1006.mocap.utils.EntityData;
import com.mt1006.mocap.utils.FakePlayer;
import com.mt1006.mocap.utils.ProfileUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayedScene
{
	private int id;
	private SceneType type;
	private String name;
	private boolean root;
	private ServerLevel level;

	private PlayerData playerData = null;
	private @Nullable String playerAsEntityID = null;
	private Vec3 offset = Vec3.ZERO;
	private int startDelay = 0;
	private int tickCounter = 0;
	private int dyingTicks = 0;
	private boolean finished = false;

	// SCENE
	private final List<PlayedScene> subscenes = new ArrayList<>();

	// RECORDING
	private RecordingData recording;
	private PlayingContext ctx;
	private int pos = 0;

	public boolean start(CommandInfo commandInfo, String name, PlayerData playerData, int id)
	{
		this.root = true;
		this.id = id;
		this.name = name;
		this.playerData = playerData;
		this.type = name.charAt(0) == '.' ? SceneType.SCENE : SceneType.RECORDING;
		this.level = commandInfo.source.getLevel();

		DataManager data = new DataManager();
		if (!data.load(commandInfo, name))
		{
			if (!data.knownError)
			{
				commandInfo.sendFailure("mocap.playing.start.error");
				commandInfo.sendFailure("mocap.playing.start.error.load");
			}
			commandInfo.sendFailure("mocap.playing.start.error.load.path", data.getResourcePath());
			return false;
		}

		switch (type)
		{
			case RECORDING: return startPlayingRecording(commandInfo, data);
			case SCENE: return startPlayingScene(commandInfo, data);
			default: return false;
		}
	}

	private boolean startSubscene(CommandInfo commandInfo, PlayedScene parent, SceneData.Subscene info, DataManager data)
	{
		this.root = false;
		this.id = 0;
		this.name = info.name;
		this.level = commandInfo.source.getLevel();
		this.type = name.charAt(0) == '.' ? SceneType.SCENE : SceneType.RECORDING;

		playerData = info.playerData.mergeWithParent(parent.playerData);
		playerAsEntityID = info.playerAsEntityID != null ? info.playerAsEntityID : parent.playerAsEntityID;
		offset = parent.offset.add(info.posOffset[0], info.posOffset[1], info.posOffset[2]);
		startDelay = (int)Math.round(info.startDelay * 20.0);

		switch (type)
		{
			case RECORDING: return startPlayingRecording(commandInfo, data);
			case SCENE: return startPlayingScene(commandInfo, data);
			default: return false;
		}
	}

	private boolean startPlayingScene(CommandInfo commandInfo, DataManager data)
	{
		SceneData sceneData = data.getScene(name);
		if (sceneData == null) { return false; }

		for (SceneData.Subscene subscene : sceneData.subscenes)
		{
			PlayedScene newScene = new PlayedScene();
			if (!newScene.startSubscene(commandInfo, this, subscene, data)) { return false; }
			subscenes.add(newScene);
		}
		return true;
	}

	/** A clone doesn't make its death sound by itself (it never really dies), so it's played here. */
	private void deathSound()
	{
		Entity e = ctx.entity;
		dev.recur.SoundLog.cloneSound(level, e.getX(), e.getY(), e.getZ(),
			net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.PLAYER_DEATH),
			net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F + 1.0F,
			level.getRandom().nextLong());
	}

	/** Kills the clone right now, the same way a recording that ends in death does. */
	public void recurKill()
	{
		if (ctx == null || finished || dyingTicks > 0) { return; }
		finished = true;
		if (fastForwarding || !(ctx.entity instanceof net.minecraft.world.entity.LivingEntity)) { stop(); return; }
		handBackBosses();
		ctx.entity.setInvulnerableTime(0); // This lets the hurt sound play.
		ctx.entity.kill(level);
		deathSound();
		dyingTicks = 20;
	}

	/**
	 * A boss the dying clone was playing goes back to the clone that had it before right away, not after the death
	 * animation (it would hang in the air for that second, then jump).
	 */
	private void handBackBosses()
	{
		for (Entity e : new java.util.ArrayList<>(ctx.entityMap.values()))
		{
			if (dev.recur.BossReplay.isBoss(e) && Controlled.controller(e) == ctx) { Controlled.letGoAtEnd(e); }
		}
	}

	public @Nullable PlayingContext recurContext() { return ctx; }

	/** The entity playing this recording (the clone), if it is playing. */
	public @Nullable net.minecraft.world.entity.Entity recurEntity() { return ctx != null ? ctx.entity : null; }

	/** When set, the clone shows this name (the skin still comes from the looked-up profile). */
	public static @Nullable String nameOverride = null;
	/** The name shown in the world, when it differs from the player name commands use. */
	public static @Nullable String worldNameOverride = null;

	/**
	 * True while a clone is being fast-forwarded to where a run starts (a checkpoint). Only where the clone is
	 * and what it holds matter then. Breaking and placing blocks, sounds, hits and pickups are skipped because the world's
	 * changes are applied separately. That way, starting at a checkpoint is instant and quiet.
	 */
	public static boolean fastForwarding = false;

	private boolean startPlayingRecording(CommandInfo commandInfo, DataManager data)
	{
		GameProfile profile = getGameProfile(commandInfo);
		if (profile == null)
		{
			commandInfo.sendFailure("mocap.playing.start.error");
			commandInfo.sendFailure("mocap.playing.start.error.profile");
			return false;
		}

		PropertyMap newPropertyMap = playerData.addSkinToPropertyMap(commandInfo, profile.properties());
		GameProfile newProfile = new GameProfile(UUID.randomUUID(), nameOverride != null ? nameOverride : profile.name(), newPropertyMap);

		recording = data.getRecording(name);
		if (recording == null) { return false; }

		PlayerList packetTargets = level.getServer().getPlayerList();
		Vec3i blockOffset = new Vec3i((int)Math.round(offset.x), (int)Math.round(offset.y), (int)Math.round(offset.z));
		Entity entity;

		if (playerAsEntityID == null)
		{
			FakePlayer fakePlayer = new FakePlayer(level, newProfile);
			fakePlayer.worldName = worldNameOverride;
			entity = fakePlayer;

			EntityData.PLAYER_SKIN_PARTS.set(fakePlayer, (byte)0b01111111);
			((dev.recur.mixin.GameModeAccessor)fakePlayer.gameMode).recur$set(Settings.USE_CREATIVE_GAME_MODE.val ? GameType.CREATIVE : GameType.SURVIVAL, GameType.SURVIVAL);
			fakePlayer.onUpdateAbilities();
			EntityState.initEntity(fakePlayer, recording, offset);

			// The world was just reset to its original state, so rewinding every block this recording
			// touches is pointless (and loads all those chunks at once, which made starting many clones lag badly).
			if (nameOverride == null) { recording.preExecute(fakePlayer, blockOffset); }

			// with the name shown in the world ("Name #N"), which differs from its player name
			packetTargets.broadcastAll(new ClientboundPlayerInfoUpdatePacket(java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), java.util.List.of(fakePlayer)));

			level.addNewPlayer(fakePlayer);

			if (!Settings.CAN_PUSH_ENTITIES.val)
			{
				for (ServerPlayer player : PlayerConnectionEvent.players)
				{
					MocapPacketS2C.sendNocolPlayerAdd(player, fakePlayer.getUUID());
					PlayerConnectionEvent.addNocolPlayer(fakePlayer.getUUID());
				}
			}

			EntityData.PLAYER_SKIN_PARTS.set(fakePlayer,(byte)0b01111111);
		}
		else
		{
			Identifier entityId = Identifier.parse(playerAsEntityID);
			Holder.Reference<EntityType<?>> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityId).orElse(null);
			entity = entityType != null ? entityType.value().create(level, EntitySpawnReason.COMMAND) : null;

			if (entity == null)
			{
				//TODO: better message (and fall back to FakePlayer)
				commandInfo.sendFailure("mocap.playing.start.warning.unknown_entity");
				return false;
			}

			EntityState.initEntity(entity, recording, offset);
			entity.setDeltaMovement(0.0, 0.0, 0.0);
			entity.setPermanentlyInvulnerable(true);
			entity.setNoGravity(true);
			if (entity instanceof Mob) { ((Mob)entity).setNoAi(true); }

			level.addFreshEntity(entity);
			recording.preExecute(entity, blockOffset);
		}

		ctx = new PlayingContext(packetTargets, entity, level, offset, blockOffset);
		return true;
	}

	public void stop()
	{
		switch (type)
		{
			case RECORDING:
				ctx.removeEntities(level);
				break;

			case SCENE:
				subscenes.forEach(PlayedScene::stop);
				break;
		}
		finished = true;
	}

	/** The clone whose recording is being played right now (it owns the projectiles it throws). */
	public static @Nullable Entity currentClone;

	public boolean onTick()
	{
		// Items the world drops because of what the clone does are already in its recording.
		dev.recur.ReplayScope.depth++;
		Entity previous = currentClone;
		currentClone = ctx != null ? ctx.entity : null;
		try { return tickScene(); }
		finally { dev.recur.ReplayScope.depth--; currentClone = previous; }
	}

	private boolean tickScene()
	{
		if (dyingTicks > 0)
		{
			dyingTicks--;
			if (dyingTicks == 0)
			{
				// done falling over: the puff of smoke, like any player or mob that dies
				level.broadcastEntityEvent(ctx.entity, (byte)60);
				ctx.entity.remove(Entity.RemovalReason.KILLED);
				ctx.entityRemoved = true;
				stop();
				return true;
			}
			return false;
		}

		if (finished) { return true; }

		if ((startDelay <= tickCounter && (!Settings.RECORDING_SYNC.val || Recording.anyInState(Recording.State.RECORDING))) || tickCounter == 0)
		{
			switch (type)
			{
				case RECORDING:
					while (true)
					{
						Action.Result result = recording.executeNext(ctx, pos++);
						if (result == Action.Result.NEXT_TICK) { ctx.recTick++; }

						if (result.endsPlayback)
						{
							if (recording.endsWithDeath && !fastForwarding)
							{
								dev.recur.LoopManager.onCloneDied(ctx.entity); // what it carried falls out (if that's on)
								ctx.entity.setInvulnerableTime(0); // so the hurt sound plays
								ctx.entity.kill(level);
								if (ctx.entity instanceof LivingEntity)
								{
									deathSound();
									dyingTicks = 20;
									handBackBosses();
								}
							}
							finished = true;
						}

						if (result.endsTick) { break; }
					}
					if (!finished) { ctx.releaseIdle(recording.lastTouch); }
					break;

				case SCENE:
					finished = true;
					for (PlayedScene scene : subscenes)
					{
						if (!scene.onTick()) { finished = false; }
					}
					break;
			}
		}

		if (root && finished && dyingTicks == 0) { stop(); }

		tickCounter++;
		return finished && dyingTicks == 0;
	}

	public int getID()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public boolean isFinished()
	{
		return finished && dyingTicks == 0;
	}

	private @Nullable GameProfile getGameProfile(CommandInfo commandInfo)
	{
		String profileName = playerData.name;
		Entity entity = commandInfo.source.getEntity();
		Level level = commandInfo.source.getLevel();

		if (profileName == null)
		{
			if (entity instanceof ServerPlayer) { profileName = ((ServerPlayer)entity).getGameProfile().name(); }
			else if (!level.players().isEmpty()) { profileName = level.players().get(0).getGameProfile().name(); }
			else { profileName = "Player"; }
		}

		return ProfileUtils.getGameProfile(commandInfo.source.getServer(), profileName);
	}

	private enum SceneType
	{
		SCENE,
		RECORDING
	}
}
