package dev.recur;

import com.mt1006.mocap.mocap.playing.PlayedScene;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.recording.Recording;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Thin wrapper over the (forked, multi-player) Mocap command interface. */
final class MocapBridge {
	private MocapBridge() {}

	private static boolean run(MinecraftServer server, ServerPlayer asPlayer, ServerLevel level, String command) {
		// What Mocap says goes nowhere (players don't see it), but is kept so a failure can be logged with its reason.
		List<String> said = new java.util.ArrayList<>();
		net.minecraft.commands.CommandSource listener = new net.minecraft.commands.CommandSource() {
			@Override public void sendSystemMessage(net.minecraft.network.chat.Component c) { said.add(c.getString()); }
			@Override public boolean acceptsSuccess() { return true; }
			@Override public boolean acceptsFailure() { return true; }
			@Override public boolean shouldInformAdmins() { return false; }
		};
		var source = asPlayer.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER).withSource(listener);
		if (level != null) source = source.withLevel(level);
		try {
			var parsed = server.getCommands().getDispatcher().parse(command, source);
			boolean ok = server.getCommands().getDispatcher().execute(parsed) > 0;
			if (!ok) Recur.LOGGER.warn("Mocap command didn't work: {} -> {}", command, said);
			return ok;
		} catch (Exception e) {
			Recur.LOGGER.error("Mocap command failed: {}", command, e);
			return false;
		}
	}

	/** Starts recording this player immediately (first call arms the recorder, second starts it). */
	static void startRecording(MinecraftServer server, ServerPlayer player) {
		Recording.discard(player.getUUID());
		String name = player.getGameProfile().name();
		run(server, player, null, "mocap recording start " + name);
		run(server, player, null, "mocap recording start " + name);
	}

	/** True while this player's recording has finished (death) and is waiting to be saved or thrown away. */
	static boolean isWaitingForDecision(ServerPlayer player) {
		Recording r = Recording.of(player);
		return r != null && r.state == Recording.State.WAITING_FOR_DECISION;
	}

	static boolean isStillRecording(ServerPlayer player) {
		Recording r = Recording.of(player);
		return r != null && r.state == Recording.State.RECORDING;
	}

	/** Ends the player's recording and saves it as the given file name. Returns false if there was nothing to save. */
	static boolean finishAndSave(MinecraftServer server, ServerPlayer player, String fileName) {
		Recording r = Recording.of(player);
		if (r == null) return false;
		if (r.state == Recording.State.RECORDING) run(server, player, null, "mocap recording stop");
		r = Recording.of(player);
		if (r == null || r.state != Recording.State.WAITING_FOR_DECISION) { Recording.discard(player.getUUID()); return false; }
		boolean ok = run(server, player, null, "mocap recording save " + fileName);
		if (!ok) Recording.discard(player.getUUID());
		return ok;
	}

	static void discard(UUID playerId) { Recording.discard(playerId); }

	/** This player's recording ends with them dying (their clone dies there, falls over and goes up in smoke). */
	static void markDeath(ServerPlayer player) {
		Recording r = Recording.active(player);
		if (r != null) r.writer.modifyBoolean(r.endsWithDeathPos, true);
	}

	/** Starts a clone in the dimension it was recorded in, named after its owner, fast-forwarded by the given ticks. */
	/** Returns the clone's entity id, or -1 if it could not be started. */
	static int playClone(MinecraftServer server, ServerPlayer commandSource, String recording, String ownerName,
						  String displayName, String dimension, int fastForwardTicks) {
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
		// The skin comes from the owner's profile; the name shown above the clone is "Owner #N".
		PlayedScene.nameOverride = LoopManager.commandName(displayName);
		PlayedScene.worldNameOverride = displayName;
		boolean ok;
		try { ok = run(server, commandSource, level, "mocap playing start " + recording + " " + ownerName); }
		finally { PlayedScene.nameOverride = null; PlayedScene.worldNameOverride = null; }
		if (!ok) return -1;
		List<PlayedScene> scenes = Playing.playedScenes;
		synchronized (scenes) {
			if (scenes.isEmpty()) return -1;
			PlayedScene scene = scenes.get(scenes.size() - 1);
			var entity = scene.recurEntity();
			if (fastForwardTicks > 0) {
				// Jump straight to that moment, quietly (see PlayedScene.fastForwarding).
				boolean silent = entity != null && entity.isSilent();
				if (entity != null) entity.setSilent(true);
				PlayedScene.fastForwarding = true;
				try {
					for (int i = 0; i < fastForwardTicks && !scene.isFinished(); i++) scene.onTick();
				} finally {
					PlayedScene.fastForwarding = false;
					if (entity != null) entity.setSilent(silent);
				}
				// Its run was already over by then, so it vanished quietly and there is no clone.
				if (scene.isFinished() || entity == null || entity.isRemoved()) { scene.stop(); return -1; }
			}
			return entity != null ? entity.getId() : -1;
		}
	}

	/** Stops just the clone playing as this entity. */
	static void stopFor(net.minecraft.world.entity.Entity entity) {
		synchronized (Playing.playedScenes) {
			for (PlayedScene scene : new ArrayList<>(Playing.playedScenes)) if (scene.recurEntity() == entity) scene.stop();
		}
	}

	/** The clone played by this entity dies right now (death animation, then it's gone). */
	static void killFor(net.minecraft.world.entity.Entity entity) {
		synchronized (Playing.playedScenes) {
			for (PlayedScene scene : new ArrayList<>(Playing.playedScenes)) if (scene.recurEntity() == entity) scene.recurKill();
		}
	}

	/** What the clone played by this entity carries right now (or, for recordings from before that was saved, what it wears). */
	static List<net.minecraft.world.item.ItemStack> carried(net.minecraft.world.entity.Entity entity) {
		List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
		com.mt1006.mocap.mocap.playing.PlayingContext ctx = null;
		synchronized (Playing.playedScenes) {
			for (PlayedScene scene : Playing.playedScenes) if (scene.recurEntity() == entity) ctx = scene.recurContext();
		}
		if (ctx != null && ctx.inventory != null) {
			var ops = entity.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
			for (String data : ctx.inventory) {
				if (data == null || data.isEmpty()) continue;
				try {
					net.minecraft.world.item.ItemStack.CODEC.parse(ops, com.mt1006.mocap.utils.Utils.nbtFromString(data)).result()
						.filter(s -> !s.isEmpty()).ifPresent(items::add);
				} catch (Exception ignored) {}
			}
		} else if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
			for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
				net.minecraft.world.item.ItemStack s = living.getItemBySlot(slot);
				if (!s.isEmpty()) items.add(s.copy());
			}
		}
		return items;
	}

	/** The experience level of the player this clone plays (0 when its recording doesn't have it). */
	static int experienceLevel(net.minecraft.world.entity.Entity entity) {
		synchronized (Playing.playedScenes) {
			for (PlayedScene scene : Playing.playedScenes) {
				if (scene.recurEntity() != entity) continue;
				var ctx = scene.recurContext();
				return ctx != null ? Math.max(0, ctx.experienceLevel) : 0;
			}
		}
		return 0;
	}

	static void stopAllPlayback() {
		synchronized (Playing.playedScenes) {
			new ArrayList<>(Playing.playedScenes).forEach(PlayedScene::stop);
		}
	}
}
