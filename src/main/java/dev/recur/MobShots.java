package dev.recur;

import com.mt1006.mocap.mocap.actions.Action;
import com.mt1006.mocap.mocap.playing.Controlled;
import com.mt1006.mocap.mocap.playing.PlayedScene;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * What a mob shot in a recording (arrows, fireballs, wither skulls, evoker fangs...), when a clone's replay gets to it.
 * It's only shot again while the clone still plays that mob: a mob that left the replay to fight someone for real
 * shoots by itself. Then it flies and hits by itself from where and as fast as it was shot, with the mob's shooting
 * sound (a mob being played back doesn't make it by itself).
 */
public final class MobShots {
	private MobShots() {}

	/** OK or IGNORED when it was a mob's shot, or null when it's something else (a player's arrow): carry on as usual. */
	public static Action.Result replay(PlayingContext ctx, Entity shot, CompoundTag tag, Vec3 at) {
		if (!(shot instanceof Projectile) && !(shot instanceof EvokerFangs)) return null;
		UUID ownerId = tag.read("Owner", UUIDUtil.CODEC).orElse(null);
		Entity owner = null;
		if (ownerId != null) {
			owner = ctx.aliases.get(ownerId); // the mob this replay moves in its place (it has another id in this run)
			if (owner == null) owner = ctx.level.getEntity(ownerId);
		}
		if (owner instanceof Player) return null; // a player's own: the clone's copy follows its path
		if (owner == null) return onlyMobsShoot(shot) ? Action.Result.IGNORED : null; // its mob isn't here at all
		if (!(owner instanceof Mob mob) || Controlled.controller(mob) != ctx) return Action.Result.IGNORED;
		// A shulker bullet homes in on whoever it was shot at, the live player in this run, so it keeps its recorded path.
		if (shot instanceof ShulkerBullet) return null;
		if (PlayedScene.fastForwarding) return Action.Result.IGNORED;

		shot.setPos(at);
		if (shot instanceof Projectile p) p.setOwner(mob);
		else if (shot instanceof EvokerFangs fangs) fangs.setOwner(mob);
		// the wither's skulls and the dragon's fireballs go for the player fighting it now
		if ((shot instanceof WitherSkull && mob instanceof net.minecraft.world.entity.boss.wither.WitherBoss)
			|| (shot instanceof DragonFireball && mob instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon))
			BossReplay.aimAtPlayer(ctx.level, mob, shot);
		if (!ctx.level.addFreshEntity(shot)) {
			shot.setUUID(UUID.randomUUID());
			if (!ctx.level.addFreshEntity(shot)) return Action.Result.IGNORED;
		}
		shootSound(ctx, mob, shot);
		return Action.Result.OK;
	}

	/** Things players can't throw or shoot themselves. */
	private static boolean onlyMobsShoot(Entity shot) {
		return (shot instanceof AbstractHurtingProjectile && !(shot instanceof WindCharge)) || shot instanceof LlamaSpit
			|| shot instanceof ShulkerBullet || shot instanceof EvokerFangs;
	}

	private static void shootSound(PlayingContext ctx, Mob mob, Entity shot) {
		SoundEvent sound;
		float volume = 1f;
		if (shot instanceof SmallFireball) sound = SoundEvents.BLAZE_SHOOT;
		else if (shot instanceof LargeFireball) { sound = SoundEvents.GHAST_SHOOT; volume = 4f; }
		else if (shot instanceof WitherSkull) { sound = SoundEvents.WITHER_SHOOT; volume = 2f; }
		else if (shot instanceof DragonFireball) { sound = SoundEvents.ENDER_DRAGON_SHOOT; volume = 4f; }
		else if (shot instanceof BreezeWindCharge) sound = SoundEvents.BREEZE_SHOOT;
		else if (shot instanceof LlamaSpit) sound = SoundEvents.LLAMA_SPIT;
		else if (shot instanceof AbstractThrownPotion) sound = SoundEvents.WITCH_THROW;
		else if (shot instanceof Snowball) sound = SoundEvents.SNOW_GOLEM_SHOOT;
		else if (shot instanceof ThrownTrident) sound = SoundEvents.DROWNED_SHOOT;
		else if (shot instanceof AbstractArrow) sound = mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob
			? SoundEvents.CROSSBOW_SHOOT : SoundEvents.SKELETON_SHOOT;
		else return; // fangs make their own sound when they bite
		var random = ctx.level.getRandom();
		SoundLog.cloneSound(ctx.level, mob.getX(), mob.getEyeY(), mob.getZ(), BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
			mob.getSoundSource(), volume, 1f / (random.nextFloat() * 0.4f + 0.8f), random.nextLong());
	}
}
