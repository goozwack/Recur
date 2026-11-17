package com.mt1006.mocap.mocap.playing;

import com.google.common.collect.ImmutableList;
import com.mt1006.mocap.events.PlayerConnectionEvent;
import com.mt1006.mocap.mocap.settings.Settings;
import com.mt1006.mocap.network.MocapPacketS2C;
import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class PlayingContext
{
	public final PlayerList packetTargets;
	public final Entity mainEntity;
	public final ServerLevel level;
	public final Vec3 offset;
	public final Vec3i blockOffset;
	public final Map<Integer, Entity> entityMap = new HashMap<>();
	/** Entities this replay moves that have another id in this run than in the recording, by their recorded id. */
	public final Map<java.util.UUID, Entity> aliases = new HashMap<>();
	public Entity entity;
	public boolean entityRemoved = false;
	private Vec3 position;
	/** What the clone carries by now, by inventory slot (see SetInventory), or null if the recording doesn't say. */
	public String[] inventory;
	/** The recorded player's experience level (-1 = not in the recording). */
	public int experienceLevel = -1;
	/** How many recorded ticks have been played. */
	public int recTick = 0;

	public PlayingContext(PlayerList packetTargets, Entity entity, ServerLevel level, Vec3 offset, Vec3i blockOffset)
	{
		this.packetTargets = packetTargets;
		this.mainEntity = entity;
		this.level = level;
		this.offset = offset;
		this.blockOffset = blockOffset;
		this.entity = entity;
		this.position = entity.position();
	}

	public void broadcast(Packet<?> packet)
	{
		packetTargets.broadcastAll(packet);
	}

	public void fluentMovement(Supplier<Packet<?>> packetSupplier)
	{
		if (Settings.FLUENT_MOVEMENTS.val == 0.0) { return; }
		Packet<?> packet = packetSupplier.get();

		if (Settings.FLUENT_MOVEMENTS.val > 0.0)
		{
			Vec3 pos = entity.position();
			double maxDistSqr = Settings.FLUENT_MOVEMENTS.val * Settings.FLUENT_MOVEMENTS.val;

			for (ServerPlayer player : packetTargets.getPlayers())
			{
				if (player.distanceToSqr(pos) > maxDistSqr) { continue; }
				player.connection.send(packet);
			}
		}
		else
		{
			packetTargets.broadcastAll(packet);
		}
	}

	public void removeEntities(ServerLevel level)
	{
		if (!entityRemoved)
		{
			if (entity instanceof FakePlayer)
			{
				FakePlayer fakePlayer = (FakePlayer)entity;
				if (PlayerConnectionEvent.nocolPlayers.contains(fakePlayer.getUUID()))
				{
					for (ServerPlayer player : PlayerConnectionEvent.players)
					{
						MocapPacketS2C.sendNocolPlayerRemove(player, fakePlayer.getUUID());
						PlayerConnectionEvent.removeNocolPlayer(fakePlayer.getUUID());
					}
				}
				broadcast(new ClientboundPlayerInfoRemovePacket(ImmutableList.of(fakePlayer.getUUID())));
				fakePlayer.remove(Entity.RemovalReason.KILLED);
			}
			else
			{
				removeEntity(entity, level);
			}
		}

		// What the clone placed or rode stays in the world as a normal entity (like it did in the original run);
		// only dropped items go away.
		for (Entity e : new java.util.ArrayList<>(entityMap.values()))
		{
			PlayingContext owner = Controlled.controller(e);
			if (owner == this && !Controlled.loose(e) && !e.isRemoved()) { Controlled.letGoAtEnd(e); }
			else if (owner == this || owner == null) { Controlled.drop(e); removeEntity(e, level); }
		}
		entityMap.clear();
	}

	/** Stops tracking an entity (another clone took it over, or it was released). */
	void forget(Entity e)
	{
		entityMap.values().removeIf((v) -> v == e);
	}

	/** Lets go of entities this recording won't touch again (see Controlled), and holds the rest in place. */
	void releaseIdle(java.util.Map<Integer, Integer> lastTouch)
	{
		if (entityMap.isEmpty()) { return; }
		Controlled.hold(this);
		java.util.List<Entity> idle = null;
		for (Map.Entry<Integer, Entity> entry : entityMap.entrySet())
		{
			Entity e = entry.getValue();
			if (Controlled.loose(e) || Controlled.controller(e) != this) { continue; }
			// a boss keeps flying its recorded fight even while it hangs still (a wither powering up)
			if (dev.recur.BossReplay.isBoss(e)) { continue; }
			Integer last = lastTouch.get(entry.getKey());
			if (last != null && recTick <= last + Controlled.RELEASE_AFTER) { continue; }
			if (idle == null) { idle = new java.util.ArrayList<>(); }
			idle.add(e);
		}
		if (idle != null) { idle.forEach(Controlled::letGo); }
	}

	public void shiftPosition(double x, double y, double z, float rotY, float rotX)
	{
		position = position.add(x, y, z);
		entity.snapTo(position.x, position.y, position.z, rotY, rotX);
	}

	private static void removeEntity(Entity entity, ServerLevel level)
	{
		switch (Settings.ENTITIES_AFTER_PLAYBACK.val)
		{
			case -1:
				entity.setNoGravity(false);
				entity.setPermanentlyInvulnerable(false);
				entity.removeTag(Playing.MOCAP_ENTITY_TAG);
				if (entity instanceof Mob) { ((Mob)entity).setNoAi(false); }
				break;

			case 0:
				break;

			case 2:
				entity.setInvulnerableTime(0); // for sound effect
				entity.kill(level);
				break;

			default:
				entity.remove(Entity.RemovalReason.KILLED);
				break;
		}
	}
}
