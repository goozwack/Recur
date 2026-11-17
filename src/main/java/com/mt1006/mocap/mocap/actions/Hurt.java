package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class Hurt implements Action
{
	public static final byte DUMMY = 0; // for future uses

	public Hurt(RecordingFiles.Reader reader)
	{
		reader.readByte();
	}

	public static void hurtEntity(Entity entity)
	{
		// A clone can't take damage, so it only shows being hurt: the red flash and the hurt sound (played by
		// each client for the damage event), with the burning version while it's on fire.
		if (entity instanceof net.minecraft.world.entity.player.Player && entity.level() instanceof net.minecraft.server.level.ServerLevel level)
		{
			var sources = level.damageSources();
			level.broadcastDamageEvent(entity, entity.isOnFire() ? sources.onFire() : sources.generic());
			return;
		}
		LivingEntity livingEntity = (entity instanceof LivingEntity) ? (LivingEntity)entity : null;
		// it keeps the health it has (the recording sets it, see SetHealth): the hit is only for show
		float health = livingEntity != null ? livingEntity.getHealth() : 0f;
		boolean invulnerable = entity.isPermanentlyInvulnerable();
		entity.setPermanentlyInvulnerable(false);
		if (livingEntity != null) { livingEntity.setHealth(livingEntity.getMaxHealth()); }

		entity.setInvulnerableTime(0);
		entity.hurt(new DamageSource(entity.level().damageSources().fellOutOfWorld().typeHolder()), 1.0f);

		if (livingEntity != null)
		{
			livingEntity.setHealth(health > 0f ? health : livingEntity.getMaxHealth());
			SetHealth.raidBar(livingEntity);
		}
		entity.setPermanentlyInvulnerable(invulnerable);
	}

	public static void write(RecordingFiles.Writer writer)
	{
		writer.addByte(Type.HURT.id);
		writer.addByte(DUMMY);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (dev.recur.BossReplay.dying(ctx.entity)) { return Result.IGNORED; } // a dragon flying its death doesn't flash red
		hurtEntity(ctx.entity);
		return Result.OK;
	}
}
