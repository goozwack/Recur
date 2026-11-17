package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enderman;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a mob is in its attacking stance. Clients draw some attacks from it: a skeleton only raises and draws
 * its bow while aggressive, zombies, piglins and vindicators raise their arms, and an angry enderman opens its mouth.
 * Stored as bits of one byte (older recordings only have the first one).
 */
public class SetMobFlags implements ComparableAction
{
	private static final int AGGRESSIVE = 1, ANGRY_ENDERMAN = 2, TARGETING_PLAYER = 4, LASER = 8, SPELL_SHIFT = 4, SPELL_MASK = 0x70, BLAZE_CHARGED = 0x80, CREEPER_SWELLING = 0x10;
	private final byte flags;

	public SetMobFlags(Entity entity)
	{
		int f = 0;
		if (entity instanceof Mob mob && mob.isAggressive()) { f |= AGGRESSIVE; }
		if (entity instanceof Enderman enderman && enderman.isCreepy()) { f |= ANGRY_ENDERMAN; }
		// going for the player whose recording it's in
		if (entity instanceof Mob mob && mob.getTarget() instanceof net.minecraft.world.entity.player.Player target
			&& target.getUUID().equals(dev.recur.EntityOwners.ownerOf(mob))) { f |= TARGETING_PLAYER; }
		// a creeper swelling up (it can't be a spellcaster, so it uses that bit)
		if (entity instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0) { f |= CREEPER_SWELLING; }
		// a blaze lighting up before it shoots
		if (entity instanceof net.minecraft.world.entity.monster.Blaze blaze && blaze.isOnFire()) { f |= BLAZE_CHARGED; }
		// a guardian's laser (on the clone, in the replay)
		if (entity instanceof net.minecraft.world.entity.monster.Guardian guardian && guardian.hasActiveAttackTarget()) { f |= LASER; }
		// an evoker's or illusioner's spell: raised arms and particles
		if (entity instanceof net.minecraft.world.entity.monster.illager.SpellcasterIllager caster)
		{
			f |= (caster.getEntityData().get(dev.recur.mixin.SpellcasterAccessor.recur$spell()) << SPELL_SHIFT) & SPELL_MASK;
		}
		flags = (byte)f;
	}

	public SetMobFlags(RecordingFiles.Reader reader)
	{
		flags = reader.readByte();
	}

	@Override public boolean differs(ComparableAction action)
	{
		return flags != ((SetMobFlags)action).flags;
	}

	@Override public void write(RecordingFiles.Writer writer, @Nullable ComparableAction action)
	{
		if (action != null && !differs(action)) { return; }
		writer.addByte(Type.SET_MOB_FLAGS.id);
		writer.addByte(flags);
	}

	@Override public Result execute(PlayingContext ctx)
	{
		if (!(ctx.entity instanceof Mob mob)) { return Result.IGNORED; }
		mob.setAggressive((flags & AGGRESSIVE) != 0);
		if (mob instanceof Enderman enderman)
		{
			enderman.getEntityData().set(dev.recur.mixin.EndermanAccessor.recur$creepy(), (flags & ANGRY_ENDERMAN) != 0);
		}
		if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper)
		{
			creeper.setSwellDir((flags & CREEPER_SWELLING) != 0 ? 1 : -1);
		}
		if (mob instanceof net.minecraft.world.entity.monster.Blaze blaze)
		{
			((dev.recur.mixin.BlazeAccessor)blaze).recur$setCharged((flags & BLAZE_CHARGED) != 0);
		}
		if (mob instanceof net.minecraft.world.entity.monster.Guardian guardian)
		{
			((dev.recur.mixin.GuardianAccessor)guardian).recur$setActiveAttackTarget((flags & LASER) != 0 ? ctx.mainEntity.getId() : 0);
		}
		if (mob instanceof net.minecraft.world.entity.monster.illager.SpellcasterIllager caster)
		{
			caster.getEntityData().set(dev.recur.mixin.SpellcasterAccessor.recur$spell(), (byte)((flags & SPELL_MASK) >> SPELL_SHIFT));
		}
		dev.recur.MobTies.onRecordedTargeting(mob, (flags & TARGETING_PLAYER) != 0);
		return Result.OK;
	}
}
