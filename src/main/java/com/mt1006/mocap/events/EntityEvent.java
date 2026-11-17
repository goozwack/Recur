package com.mt1006.mocap.events;

import com.mt1006.mocap.mocap.actions.EntityUpdate;
import com.mt1006.mocap.mocap.actions.Hurt;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.recording.Recording;
import com.mt1006.mocap.mocap.recording.TrackedEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class EntityEvent
{
	public static boolean onEntityHurt(LivingEntity entity, DamageSource source, float amount)
	{
		if (Recording.anyRecording() && entity.level() instanceof ServerLevel)
		{
			Recording own = Recording.active(entity);
			if (own != null)
			{
				Hurt.write(own.writer);
			}
			else
			{
				for (Recording recording : Recording.sessions())
				{
					if (recording.state != Recording.State.RECORDING) { continue; }
					TrackedEntity trackedEntity = recording.getTracked(entity);
					if (trackedEntity != null) { new EntityUpdate(EntityUpdate.HURT, trackedEntity.id).write(recording.writer); }
				}
			}
		}
		return true;
	}

	/**
	 * An entity event of a mob in a recording (a warden's or iron golem's attack swing, a sheep eating grass,
	 * hearts...) is kept, so its replay shows it too. Death has its own action, and swapping hands shows in the items.
	 */
	public static void onEntityEvent(Entity entity, byte event)
	{
		if (event == 3 || event == 55 || event == 60) { return; }
		if (!Recording.anyRecording() || entity instanceof net.minecraft.world.entity.player.Player
			|| com.mt1006.mocap.mocap.playing.Controlled.isControlled(entity)) { return; }
		for (Recording recording : Recording.sessions())
		{
			if (recording.state != Recording.State.RECORDING) { continue; }
			TrackedEntity trackedEntity = recording.getTracked(entity);
			if (trackedEntity != null) { new EntityUpdate(trackedEntity.id, event).write(recording.writer); }
		}
	}

	public static boolean onEntityDrop(LivingEntity entity)
	{
		return Playing.playedScenes.size() > 0 && entity.entityTags().contains(Playing.MOCAP_ENTITY_TAG);
	}
}
