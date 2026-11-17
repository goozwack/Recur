package dev.recur;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Recur implements ModInitializer {
	public static final String MOD_ID = "recur";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LoopNetworking.register();

		ServerTickEvents.END_SERVER_TICK.register(LoopManager::onServerTick);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> { Legacy.migrateWorld(server); WorldGuard.clear(); LoopManager.shutdown(); TimedReset.shutdown(); });
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			WorldGuard.check(server);
			ServerSettings.load(server);
			if (WorldGuard.allowed()) {
				TimedReset.load(server);
				LoopManager.loadSaved(server);
				// Both halves of timed resets must agree (an older save may only have one of them).
				if (LoopManager.isTimed() && !TimedReset.isActive()) TimedReset.forceOn();
				else if (TimedReset.isActive() && !LoopManager.isTimed()) {
					TimedReset.shutdown();
					if (!LoopManager.isActive()) LoopOverlay.active = false;
				}
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(TimedReset::tick);
		ServerTickEvents.END_SERVER_TICK.register(Reservations::tick);
		ServerTickEvents.END_SERVER_TICK.register(MobTies::tick);
		// a mob hurting a player, or trying to (it hit a shield, armour took it all...)
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (source.getEntity() != null) MobTies.onAttempt(source.getEntity(), entity);
			return true;
		});
		ServerTickEvents.END_SERVER_TICK.register(BossReplay::tick);
		// Remember the countdown and where graves' items are, so timed resets carry on after a restart.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (TimedReset.isActive()) TimedReset.save(server); });
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(TimedReset::onChunkLoad);
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive) LoopManager.onEndExit(oldPlayer, newPlayer); // The player is alive, so this respawn comes from leaving the End.
			else LoopManager.onRespawn(newPlayer);
		});
		// When a player goes through a portal, the clone of this run will go through at the same moment.
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register((original, copy, origin, destination) ->
			com.mt1006.mocap.mocap.playing.Controlled.changedDimension(original, copy));
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			if (!(player instanceof com.mt1006.mocap.utils.FakePlayer)) LoopManager.onChangeDimension(player);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(WorldReset::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LoopManager.shutdown(); TimedReset.shutdown(); WorldGuard.clear(); });
		// World changes clones should repeat (containers are caught when closed, signs when edited).
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			WorldLog.onUseBlock(player, level, hit.getBlockPos(), hit.getDirection());
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) ->
			WorldLog.onBlockBroken(player, level, pos, state));
		ServerTickEvents.END_SERVER_TICK.register(server -> { CloneLids.tick(); CloneTickets.tick(server); EntityRestore.tick(server); ReplayScope.reset(); });
		// Checkpoints put entities back as they were; entities that load later are fixed up as they load.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			EntityRestore.onLoad(entity);
			TimedReset.onEntityLoad(entity);
			BossReplay.onCrystalLoad(entity);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> EntityRestore.onUnload(entity));
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			// Timed resets: an item frame / armor stand a clone takes from later can't be used until then.
			if (Reservations.refuse(player, entity)) return net.minecraft.world.InteractionResult.FAIL;
			if (!level.isClientSide()) MobTies.direct(player, entity); // using or leashing a mob ties it to you
			WorldLog.onEntityInteract(player, entity);
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			// Crouching and hitting a clone kills it. It dies there again in later runs while its killer is alive.
			if (entity instanceof com.mt1006.mocap.utils.FakePlayer && !(player instanceof com.mt1006.mocap.utils.FakePlayer)) {
				if (!level.isClientSide() && player.isShiftKeyDown() && player instanceof ServerPlayer sp) LoopManager.killClone(sp, entity);
				return net.minecraft.world.InteractionResult.FAIL;
			}
			if (Reservations.refuse(player, entity)) return net.minecraft.world.InteractionResult.FAIL;
			// Hitting a mob ties it to you. A clone playing it back lets go first, so the hit lands.
			if (!level.isClientSide()) MobTies.direct(player, entity);
			WorldLog.onEntityInteract(player, entity);
			return net.minecraft.world.InteractionResult.PASS;
		});
		// Mobs and other entities a player hurts or kills (by hand or projectile) are recorded too.
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> WorldLog.onEntityHurt(entity, source, taken));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> WorldLog.onEntityHurt(entity, source, 0));
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
			LoopManager.onChat(sender, message.signedContent()));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (!(entity instanceof ServerPlayer p)) return true;
			if (!LoopManager.allowDeath(p, source)) return false;
			GraveMods.beforeDeath(p);
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p) LoopManager.onPlayerDeath(p);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// A world made without Recur (or a Flashback replay): everyone can play, Recur just stays switched off.
			if (!WorldGuard.allowed()) {
				LoopManager.onJoin(server, handler.getPlayer());
				return;
			}
			// The loading screen, clone fading, menus and pushing all happen on the client, so it needs Recur too.
			if (!LoopNetworking.hasRecur(handler.getPlayer())) {
				handler.disconnect(Component.literal("This world uses Recur.\nInstall Recur (and optionally Simple Voice Chat) to join."));
				return;
			}
			LoopManager.onJoin(server, handler.getPlayer());
			// Offer the walkthrough of how Recur works. The player's game only shows it once per account.
			if (WorldGuard.allowed()) LoopNetworking.sendPrompt(handler.getPlayer(), LoopNetworking.PROMPT_TUTORIAL, 0, "", "");
			ServerSettings.sync(handler.getPlayer());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LoopManager.onDisconnect(server, handler.getPlayer()));

		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> dispatcher.register(
			Commands.literal("loop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("start").executes(c -> {
					if (!WorldGuard.allowed()) {
						c.getSource().sendFailure(Component.literal("Recur is switched off in this world: it wasn't created with Recur."));
						return 0;
					}
					LoopManager.start(c.getSource().getServer(), c.getSource().getPlayer());
					return 1;
				}))
				.then(Commands.literal("stop").executes(c -> {
					LoopManager.stop(c.getSource().getServer());
					return 1;
				}))
				// In the other mode the whole world resets every few minutes and everyone keeps their stuff.
				.then(Commands.literal("timed")
					.then(Commands.literal("start")
						.executes(c -> timedStart(c.getSource(), -1))
						.then(Commands.argument("seconds", IntegerArgumentType.integer(10))
							.executes(c -> timedStart(c.getSource(), IntegerArgumentType.getInteger(c, "seconds")))))
					.then(Commands.literal("stop").executes(c -> {
						if (LoopManager.isTimed()) LoopManager.stop(c.getSource().getServer());
						return 1;
					}))
					.then(Commands.literal("reset").executes(c -> {
						LoopManager.timedResetNow(c.getSource().getServer());
						return 1;
					}))
					.then(Commands.literal("interval").then(Commands.argument("seconds", IntegerArgumentType.integer(10))
						.executes(c -> {
							TimedReset.setInterval(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "seconds") * 20);
							c.getSource().sendSuccess(() -> Component.literal("The world now resets every "
								+ TimedReset.intervalTicks() / 20 + " seconds."), false);
							return 1;
						})))
					// Every anti-dupe rule of timed resets at once (on by default).
					.then(Commands.literal("antidupe")
						.executes(c -> {
							boolean on = TimedReset.antiDupeSetting();
							c.getSource().sendSuccess(() -> Component.literal("Anti-dupe rules are " + (on ? "on." : "off.")), false);
							return 1;
						})
						.then(Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
							.executes(c -> {
								boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "enabled");
								TimedReset.setAntiDupe(c.getSource().getServer(), on);
								c.getSource().sendSuccess(() -> Component.literal(on
									? "Anti-dupe rules are on. Items clones take out of containers are locked again from the next reset."
									: "Anti-dupe rules are off: anything clones make, drop or take can be mined, picked up and taken."), true);
								return 1;
							})))
					.then(Commands.literal("clearblocks").then(Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
						.executes(c -> {
							boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "enabled");
							TimedReset.setClearAtPlayers(c.getSource().getServer(), on);
							c.getSource().sendSuccess(() -> Component.literal(on
								? "After a reset, solid blocks where players stand are cleared."
								: "After a reset, blocks where players stand are left as they are."), false);
							return 1;
						}))))
				.then(Commands.literal("timeout").then(Commands.argument("seconds", IntegerArgumentType.integer(1))
					.executes(c -> {
						LoopManager.setTimeoutTicks(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "seconds") * 20);
						return 1;
					})))
				.then(Commands.literal("loadpercent").then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
					.executes(c -> {
						ServerSettings.loadPercent = IntegerArgumentType.getInteger(c, "percent");
						ServerSettings.save(c.getSource().getServer());
						c.getSource().sendSuccess(() -> Component.literal("Runs now start once " + ServerSettings.loadPercent
							+ "% of the area around each player has loaded."), false);
						return 1;
					})))
				.then(Commands.literal("smartreset").then(Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
					.executes(c -> {
						ServerSettings.smartReset = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "enabled");
						ServerSettings.save(c.getSource().getServer());
						c.getSource().sendSuccess(() -> Component.literal(ServerSettings.smartReset
							? "Resets now put back only the chunks that changed."
							: "Resets now unload and reload the whole world."), false);
						return 1;
					})))
				.then(Commands.literal("checkpoint").executes(c -> {
					LoopManager.onAction(c.getSource().getPlayerOrException(), LoopNetworking.ACTION_CREATE, 0);
					return 1;
				}))
				.then(Commands.literal("vote").then(Commands.argument("id", IntegerArgumentType.integer(-1))
					.executes(c -> {
						LoopManager.onAction(c.getSource().getPlayerOrException(), LoopNetworking.ACTION_VOTE, IntegerArgumentType.getInteger(c, "id"));
						return 1;
					})))
				.then(Commands.literal("reset").executes(c -> {
					c.getSource().getPlayerOrException().kill(c.getSource().getLevel());
					return 1;
				}))
				.then(Commands.literal("undo")
					.executes(c -> undo(c.getSource(), 1))
					.then(Commands.argument("count", IntegerArgumentType.integer(1)).executes(c -> undo(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
				.then(Commands.literal("status").executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("Active: " + LoopManager.isActive()
						+ ", clones: " + LoopManager.cloneCount()
						+ ", time left: " + (LoopManager.remainingTicks() < 0 ? "no limit" : LoopManager.remainingTicks() / 20 + "s")
						+ ", load before start: " + ServerSettings.loadPercent + "%"
						+ ", smart reset: " + (ServerSettings.smartReset ? "on" : "off")), false);
					return 1;
				}))));
	}

	private static int timedStart(net.minecraft.commands.CommandSourceStack source, int seconds) {
		net.minecraft.server.MinecraftServer server = source.getServer();
		if (!WorldGuard.allowed()) {
			source.sendFailure(Component.literal("Recur is switched off in this world: it wasn't created with Recur."));
			return 0;
		}
		if (LoopManager.isActive()) { source.sendFailure(Component.literal("Stop the loop first (/loop stop).")); return 0; }
		if (seconds > 0) TimedReset.setInterval(server, seconds * 20);
		return LoopManager.startTimed(server, source.getPlayer()) ? 1 : 0;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** /loop undo [count]: removes your newest clones (anyone's, from the console). */
	private static int undo(net.minecraft.commands.CommandSourceStack source, int count) {
		java.util.UUID owner = source.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
		int removed = LoopManager.undo(source.getServer(), owner, count);
		if (removed == 0) { source.sendFailure(Component.literal(LoopManager.isActive() ? "There's no clone to remove." : "No loop is running.")); return 0; }
		source.sendSuccess(() -> Component.literal("Removed " + (removed == 1 ? "the newest clone" : "the " + removed + " newest clones")
			+ ". What " + (removed == 1 ? "it" : "they") + " did is gone from the next run on."), true);
		return removed;
	}

}
