package dev.recur.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.recur.LoopNetworking;
import dev.recur.Recur;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

public class RecurClient implements ClientModInitializer {
	private static KeyMapping createCheckpoint;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Recur.id("main"));
		createCheckpoint = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.recur.checkpoint", InputConstants.KEY_K, category));

		// Watching a Flashback replay, every Recur packet in it is ignored: no timer, menus, loading screens or prompts,
		// and clones are just drawn as the players they look like.
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.CloneEffectPayload.TYPE, (payload, ctx) -> CloneSounds.onEffect(payload));
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.StatePayload.TYPE, (payload, ctx) -> { if (!inReplay()) ClientLoopState.apply(payload); });
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.ClonesPayload.TYPE, (payload, ctx) -> { if (!inReplay()) ClientLoopState.onClones(payload.ids(), payload.ages(), payload.fadeIn()); });
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.PromptPayload.TYPE, (payload, ctx) -> { if (!inReplay()) onPrompt(payload); });
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.NoLootPayload.TYPE, (payload, ctx) -> ClientLoot.onNoLoot(payload));
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.LockedSlotsPayload.TYPE, (payload, ctx) -> ClientLoot.onLocked(payload));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> { ClientLoopState.clearAll(); ClientWorldSettings.clear(); Thumbnails.clear(); PROMPTS.clear(); tutorialQueued = false; ClientLoot.clear(); }));
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.SettingsPayload.TYPE, (payload, ctx) -> {
			ClientWorldSettings.set(payload.values(), payload.canEdit());
			// the settings screen was waiting for them
			if (ctx.client().gui.screen() instanceof RecurConfigScreen open && open.waiting()) ctx.client().gui.setScreen(open.reopen());
		});
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.ThumbPayload.TYPE, (payload, ctx) -> { if (!inReplay()) Thumbnails.onThumbnail(payload.id(), payload.data()); });
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.ResetPayload.TYPE, (payload, ctx) -> { if (!inReplay()) ClientLoopState.onReset(payload.start()); });
		ClientPlayNetworking.registerGlobalReceiver(LoopNetworking.CloneChatPayload.TYPE, (payload, ctx) -> {
			if (!ClientSettings.chatMuted && !inReplay())
				ctx.client().gui.hud.getChat().addClientSystemMessage(dev.recur.CloneChat.format(payload.speaker(), payload.text()));
		});
		ClientSettings.load();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ClientLoopState.tick();
			CloneCulling.tick(mc);
			CloneVoice.tick(mc);
			Thumbnails.tick();
			ClientLoot.tick(mc);
			while (createCheckpoint.consumeClick()) {
				if (ClientLoopState.active && ClientLoopState.timedTicks < 0 && !inReplay()) Thumbnails.createCheckpoint();
			}
			// Questions from the server (accept a checkpoint, the tutorial) wait until no other screen is open.
			if (!PROMPTS.isEmpty() && mc.level != null && mc.gui.screen() == null && ClientLoopState.resetTicks < 0)
				mc.gui.setScreen(PROMPTS.removeFirst().get());
		});

		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Recur.id("hud"), LoopHud::render);
	}

	private static final java.util.ArrayDeque<java.util.function.Supplier<net.minecraft.client.gui.screens.Screen>> PROMPTS = new java.util.ArrayDeque<>();

	/** The tutorial is waiting to be shown (joining twice before it opens doesn't queue it twice). */
	private static boolean tutorialQueued;

	private static void onPrompt(LoopNetworking.PromptPayload p) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		switch (p.kind()) {
			// You just made this checkpoint, so ask for its name straight away. The picture has already been taken, so no screen is open.
			case LoopNetworking.PROMPT_NAME -> mc.gui.setScreen(new RenameScreen(null, p.id(), p.name(), true));
			case LoopNetworking.PROMPT_ACCEPT -> PROMPTS.add(() -> new AcceptCheckpointScreen(p.id(), p.name(), p.creator()));
			// The server offers the tutorial on every join, and this shows it once per Minecraft account.
			case LoopNetworking.PROMPT_TUTORIAL -> {
				if (!ClientSettings.tutorialSeen() && !tutorialQueued) {
					tutorialQueued = true;
					PROMPTS.add(() -> { ClientSettings.markTutorialSeen(); tutorialQueued = false; return new TutorialScreen(); });
				}
			}
			case LoopNetworking.PROMPT_START_PICTURE -> Thumbnails.captureLoopStart();
			default -> {}
		}
	}

	/** The key bound to "create a checkpoint", as shown to the player (e.g. "K"). */
	public static String checkpointKeyName() {
		return createCheckpoint != null ? createCheckpoint.getTranslatedKeyMessage().getString() : "K";
	}

	private static java.lang.reflect.Method flashbackInReplay;
	private static boolean flashbackChecked;

	/** True while watching a Flashback replay. Recur stays switched off during replays. */
	public static boolean inReplay() {
		if (!flashbackChecked) {
			flashbackChecked = true;
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("flashback")) {
				try { flashbackInReplay = Class.forName("com.moulberry.flashback.Flashback").getMethod("isInReplay"); }
				catch (Exception e) { Recur.LOGGER.warn("Could not hook into Flashback", e); }
			}
		}
		if (flashbackInReplay == null) return false;
		try { return (Boolean) flashbackInReplay.invoke(null); } catch (Exception e) { return false; }
	}
}
