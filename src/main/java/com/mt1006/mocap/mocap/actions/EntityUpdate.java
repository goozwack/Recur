package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mixin.fabric.EntityIdMixin;
import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import com.mt1006.mocap.mocap.settings.Settings;
import com.mt1006.mocap.utils.Utils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class EntityUpdate implements Action
{
	public static final byte ADD = 1;
	public static final byte REMOVE = 2;
	public static final byte KILL = 3;
	/** How far (blocks) a rideable may be from where the recording has it for a clone to take it over. */
	private static final double MAX_VEHICLE_DISTANCE = 10.0;
	public static final byte HURT = 4;
	public static final byte PLAYER_MOUNT = 5;
	public static final byte PLAYER_DISMOUNT = 6;
	/** The recording stops moving this entity (someone else moves it now, or it went out of reach). */
	public static final byte RELEASE = 7;
	/** The player got on a vehicle that someone else drives, so it isn't in this recording. It is found by its UUID. */
	public static final byte MOUNT_UUID = 8;
	/** An entity event (a warden's or iron golem's attack, a sheep eating grass...), which clients animate. */
	public static final byte ENTITY_EVENT = 9;
	private final byte type;
	private byte event;
	private final int id;
	private final @Nullable String nbtString;
	private final double[] position = new double[3];

	public EntityUpdate(byte type)
	{
		this.type = type;
		this.id = 0;
		this.nbtString = null;
	}

	public EntityUpdate(byte type, int id)
	{
		this.type = type;
		this.id = id;
		this.nbtString = null;
	}

	public EntityUpdate(int id, byte event)
	{
		this(ENTITY_EVENT, id);
		this.event = event;
	}

	public EntityUpdate(byte type, java.util.UUID uuid)
	{
		this.type = type;
		this.id = 0;
		this.nbtString = uuid.toString();
	}

	public byte type() { return type; }
	public int id() { return id; }

	/** True if this update is about one of the recording's entities (by id). */
	public boolean refersToId() { return type != PLAYER_DISMOUNT && type != MOUNT_UUID; }

	public EntityUpdate(byte type, int id, Entity entity)
	{
		this.type = type;
		this.id = id;

		// The UUID is kept, so in later runs the clone takes over the same entity instead of making a copy.
		CompoundTag tag = serializeEntityNBT(entity);
		tag.store("UUID", net.minecraft.core.UUIDUtil.CODEC, entity.getUUID());
		// Items and experience fly off the same way. What a mob shot flies by itself in the replay, as fast as it was shot.
		if (com.mt1006.mocap.mocap.playing.Controlled.loose(entity) || entity instanceof net.minecraft.world.entity.projectile.Projectile)
		{
			tag.store("Motion", Vec3.CODEC, entity.getDeltaMovement());
		}
		java.util.UUID droppedBy = dev.recur.MobDrops.droppedBy(entity);
		if (droppedBy != null) { tag.putString(dev.recur.MobDrops.TAG, droppedBy.toString()); }
		String reward = dev.recur.MobDrops.rewardOf(entity);
		if (reward != null) { tag.putString(dev.recur.MobDrops.REWARD_TAG, reward); }
		String fight = dev.recur.TrialSpawns.fightOf(entity);
		if (fight != null) { tag.putString(dev.recur.TrialSpawns.TAG, fight); }
		nbtString = tag.toString();

		Vec3 entityPos = entity.position();
		this.position[0] = entityPos.x;
		this.position[1] = entityPos.y;
		this.position[2] = entityPos.z;
	}

	public EntityUpdate(RecordingFiles.Reader reader)
	{
		type = reader.readByte();
		id = reader.readInt();

		if (type == ADD)
		{
			nbtString = reader.readString();
			position[0] = reader.readDouble();
			position[1] = reader.readDouble();
			position[2] = reader.readDouble();
		}
		else if (type == MOUNT_UUID)
		{
			nbtString = reader.readString();
		}
		else
		{
			nbtString = null;
			if (type == ENTITY_EVENT) { event = reader.readByte(); }
		}
	}

	/** Gets the clone on the vehicle; as the driver (first passenger) if it is the one moving the vehicle. */
	private static void mount(PlayingContext ctx, Entity vehicle, boolean driver)
	{
		ctx.entity.startRiding(vehicle, true, true);
		if (!driver || ctx.entity.getVehicle() != vehicle || vehicle.getFirstPassenger() == ctx.entity) { return; }
		java.util.List<Entity> passengers = new java.util.ArrayList<>(vehicle.getPassengers());
		passengers.remove(ctx.entity);
		passengers.add(0, ctx.entity);
		((dev.recur.mixin.EntityPassengersAccessor)vehicle).recur$setPassengers(com.google.common.collect.ImmutableList.copyOf(passengers));
	}

	public static CompoundTag serializeEntityNBT(Entity entity)
	{
		TagValueOutput nbt = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());

		String id = ((EntityIdMixin)entity).callGetEncodeId();
		nbt.putString("id", id != null ? id : "minecraft:cow");
		entity.saveWithoutId(nbt);

		CompoundTag compoundTag = nbt.buildResult();
		compoundTag.remove("UUID");
		compoundTag.remove("Pos");
		compoundTag.remove("Motion");
		return compoundTag;
	}

	public static boolean isEntityPlayingDisabled()
	{
		return !(Settings.PLAY_BLOCK_ACTIONS.val || Settings.PLAY_ITEM_ENTITIES.val || Settings.PLAY_OTHER_ENTITIES.val);
	}

	public void write(RecordingFiles.Writer writer)
	{
		writer.addByte(Type.ENTITY_UPDATE.id);

		writer.addByte(type);
		writer.addInt(id);

		if (type == ADD)
		{
			writer.addString(nbtString != null ? nbtString : "");
			writer.addDouble(position[0]);
			writer.addDouble(position[1]);
			writer.addDouble(position[2]);
		}
		else if (type == MOUNT_UUID)
		{
			writer.addString(nbtString != null ? nbtString : "");
		}
		else if (type == ENTITY_EVENT)
		{
			writer.addByte(event);
		}
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (type == ADD)
		{
			if (nbtString == null || ctx.entityMap.containsKey(id) || isEntityPlayingDisabled()) { return Result.IGNORED; }

			CompoundTag compoundTag;
 			try { compoundTag = Utils.nbtFromString(nbtString); }
			catch (Exception exception) { return Result.ERROR; }
			ValueInput nbt = TagValueInput.create(ProblemReporter.DISCARDING, ctx.entity.registryAccess(), compoundTag);

			// The entity is already there (it never left, a checkpoint put it back, or another clone or a player
			// moved it last): this clone takes it over from where the recording has it, instead of making a copy.
			// A trial spawner's mob only comes if that clone's fight happened in this run (nobody got there first).
			if (!dev.recur.TrialSpawns.addsHere(compoundTag)) { return Result.IGNORED; }
			java.util.UUID uuid = compoundTag.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
			Entity existing = uuid != null ? ctx.level.getEntity(uuid) : null;
			// There's only ever one dragon. The clone takes over the real one, waiting for it to load if it hasn't yet,
			// and never makes a copy.
			// A crystal is never put on top of another one: the clone takes over the one already there.
			if (existing == null && "minecraft:end_crystal".equals(compoundTag.getStringOr("id", "")))
			{
				existing = dev.recur.BossReplay.crystalAt(ctx.level, new Vec3(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z));
			}
			// There's one wither in a fight: a clone takes over the one that's there, wherever the clone before it flew it,
			// and never makes a second one (that one would have the health it had in this recording).
			if (existing == null && "minecraft:wither".equals(compoundTag.getStringOr("id", "")))
			{
				existing = dev.recur.BossReplay.nearestOfType(ctx.level, "minecraft:wither",
					net.minecraft.core.BlockPos.containing(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z));
			}
			// A mob that spawned anew this run (a raid wave) has a new id: the clone takes over the same kind of mob
			// nearby instead of making a copy.
			if (existing == null && !"minecraft:ender_dragon".equals(compoundTag.getStringOr("id", "")))
			{
				existing = dev.recur.MobTies.standIn(ctx, compoundTag.getStringOr("id", ""), new Vec3(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z));
			}
			if ("minecraft:ender_dragon".equals(compoundTag.getStringOr("id", "")))
			{
				if (isEntityPlayingDisabled() || ctx.entityMap.containsKey(id)) { return Result.IGNORED; }
				dev.recur.BossReplay.takeDragon(ctx, id, uuid, new Vec3(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z));
				return Result.OK;
			}
			if (existing != null && !existing.isRemoved() && !(existing instanceof net.minecraft.world.entity.player.Player))
			{
				// A horse, boat, minecart or other rideable is only taken over (and moved back to where the recording has
				// it) when nobody else is riding it and it is within 10 blocks of that spot. Otherwise it is left alone and the
				// clone doesn't get on it.
				if (existing instanceof PlayerRideable || existing instanceof net.minecraft.world.entity.vehicle.VehicleEntity)
				{
					boolean riddenByOther = false;
					for (Entity passenger : existing.getPassengers()) { if (passenger != ctx.entity) { riddenByOther = true; } }
					double rx = position[0] + ctx.offset.x, ry = position[1] + ctx.offset.y, rz = position[2] + ctx.offset.z;
					if (riddenByOther || existing.distanceToSqr(rx, ry, rz) > MAX_VEHICLE_DISTANCE * MAX_VEHICLE_DISTANCE) { return Result.IGNORED; }
				}
				Vec3 was = existing.position();
				if (!com.mt1006.mocap.mocap.playing.Controlled.loose(existing)) // An item stays wherever it landed.
					existing.snapTo(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z, existing.getYRot(), existing.getXRot());
				dev.recur.TrialSpawns.onTaken(compoundTag, existing); // before take: it isn't made invulnerable
				com.mt1006.mocap.mocap.playing.Controlled.take(ctx, existing);
				com.mt1006.mocap.mocap.playing.Controlled.glideFrom(existing, was); // a boss glides there instead of jumping
				ctx.entityMap.put(id, existing);
				// it has another id in this run: what the recording says it shot still comes from it (see MobShots)
				if (uuid != null && !uuid.equals(existing.getUUID())) { ctx.aliases.put(uuid, existing); com.mt1006.mocap.mocap.playing.Controlled.alias(uuid, existing); }
				return Result.OK;
			}

			// What a mob dropped when it died only drops if the mob died in this replay too.
			if (!dev.recur.MobDrops.dropsHere(ctx, compoundTag)) { return Result.IGNORED; }
			Entity entity = EntityType.create(nbt, ctx.level, new EntitySpawnRequest(EntitySpawnReason.COMMAND, false)).orElse(null);
			if (entity == null) { return Result.IGNORED; }

			// What a mob shot (an arrow, a fireball, a wither skull...) flies and hits by itself (see MobShots).
			Result mobShot = dev.recur.MobShots.replay(ctx, entity, compoundTag,
				new Vec3(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z));
			if (mobShot != null) { return mobShot; }

			if (entity instanceof PlayerRideable || entity instanceof Minecart || entity instanceof Boat)
			{
				if (!Settings.PLAY_VEHICLE_ENTITIES.val) { return Result.IGNORED; }
			}
			else if (com.mt1006.mocap.mocap.playing.Controlled.loose(entity))
			{
				if (!Settings.PLAY_ITEM_ENTITIES.val) { return Result.IGNORED; }
				if (com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding) { return Result.IGNORED; } // Dropped long ago
			}
			else if (!Settings.PLAY_OTHER_ENTITIES.val)
			{
				return Result.IGNORED;
			}

			entity.setPos(position[0] + ctx.offset.x, position[1] + ctx.offset.y, position[2] + ctx.offset.z);
			com.mt1006.mocap.mocap.playing.Controlled.take(ctx, entity);

			if (!ctx.level.addFreshEntity(entity))
			{
				// Its UUID is still taken by an entity that isn't loaded, so it gets a new one.
				entity.setUUID(java.util.UUID.randomUUID());
				if (!ctx.level.addFreshEntity(entity)) { com.mt1006.mocap.mocap.playing.Controlled.abandon(entity); return Result.IGNORED; }
			}
			ctx.entityMap.put(id, entity);
			if (uuid != null && !uuid.equals(entity.getUUID())) { ctx.aliases.put(uuid, entity); com.mt1006.mocap.mocap.playing.Controlled.alias(uuid, entity); }
			// A firework only makes its launch sound on its first tick, which had already passed when the recording
			// saw it, so the launch is heard here instead.
			if (entity instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity && compoundTag.getIntOr("Life", 0) > 0
				&& !com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding)
			{
				dev.recur.SoundLog.cloneSound(ctx.level, entity.getX(), entity.getY(), entity.getZ(),
					net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_LAUNCH), net.minecraft.sounds.SoundSource.AMBIENT,
					3.0F, 1.0F, ctx.level.getRandom().nextLong());
			}
			return Result.OK;
		}
		else if (type == PLAYER_DISMOUNT)
		{
			ctx.entity.stopRiding();
			return Result.OK;
		}
		else if (type == MOUNT_UUID)
		{
			java.util.UUID uuid;
			try { uuid = java.util.UUID.fromString(nbtString); }
			catch (Exception e) { return Result.IGNORED; }
			Entity vehicle = ctx.level.getEntity(uuid);
			if (vehicle == null || vehicle.isRemoved()) { return Result.IGNORED; }
			mount(ctx, vehicle, false);
			return Result.OK;
		}
		else
		{
			Entity entity = ctx.entityMap.get(id);
			if (entity == null) { return Result.IGNORED; }

			if (type == REMOVE)
			{
				// An item vanishing right next to the clone was picked up, so show the pickup animation
				// (item flying into the clone) like vanilla does, instead of it just disappearing.
				// Experience flies in the same way, with its ding.
				if (!com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding
					&& com.mt1006.mocap.mocap.playing.Controlled.loose(entity) && ctx.entity != entity && entity.distanceToSqr(ctx.entity) < 9.0)
				{
					int count = entity instanceof ItemEntity item ? item.getItem().getCount() : 1;
					ctx.level.getChunkSource().sendToTrackingPlayers(entity,
						new net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket(entity.getId(), ctx.entity.getId(), count));
				}
				// A clone's wind charge bursts where it hit: the gust, its sound, and the push.
				if (entity instanceof net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge charge
					&& !com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding)
				{
					((dev.recur.mixin.WindChargeAccessor)charge).recur$explode(charge.position());
				}
				// A crystal that was blown up blows up again.
				if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal && !com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding)
				{
					dev.recur.BossReplay.explodeCrystal(ctx.level, crystal);
					return Result.OK;
				}
				entity.remove(Entity.RemovalReason.KILLED);
				return Result.OK;
			}
			else if (type == KILL)
			{
				// When fast-forwarding, it died long ago and is removed quietly.
				if (com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding) { entity.discard(); return Result.OK; }
				dev.recur.MobDrops.killedByReplay(entity);
				entity.setInvulnerableTime(0); // for sound effect
				entity.kill(ctx.level);
				return Result.OK;
			}
			else if (type == HURT)
			{
				if (com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding) { return Result.OK; }
				// A boss gets the real damage players did to it instead (see BossReplay), so this hurt-and-heal is skipped.
				if (dev.recur.BossReplay.isBoss(entity)) { return Result.OK; }
				Hurt.hurtEntity(entity);
				return Result.OK;
			}
			else if (type == PLAYER_MOUNT)
			{
				// The clone drives what its recording moves. Anyone already in the driver's seat moves back a seat.
				mount(ctx, entity, com.mt1006.mocap.mocap.playing.Controlled.controller(entity) == ctx);
				return Result.OK;
			}
			else if (type == ENTITY_EVENT)
			{
				if (!com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding) { ctx.level.broadcastEntityEvent(entity, event); }
				return Result.OK;
			}
			else if (type == RELEASE)
			{
				if (com.mt1006.mocap.mocap.playing.Controlled.controller(entity) == ctx) { com.mt1006.mocap.mocap.playing.Controlled.letGo(entity); }
				else { ctx.entityMap.remove(id); }
				return Result.OK;
			}
			else
			{
				return Result.IGNORED;
			}
		}
	}
}
