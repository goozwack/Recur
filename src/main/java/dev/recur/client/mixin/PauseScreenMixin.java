package dev.recur.client.mixin;

import dev.recur.LoopNetworking;
import dev.recur.client.CheckpointScreen;
import dev.recur.client.ClientLoopState;
import dev.recur.client.ClientSettings;
import dev.recur.client.LoopButton;
import dev.recur.client.LoopSlider;
import dev.recur.client.PixelIcon;
import dev.recur.client.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recur's bar along the bottom of the pause menu: one line of loop info, and a row of controls below it
 * (checkpoints, clone visibility, clone voice volume and clone chat).
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) { super(title); }

	@Unique private static final int BAR_H = 46, ROW_H = 20, GAP = 3, GROUP_GAP = 10;
	@Unique private LoopButton recur$muteButton;
	@Unique private LoopSlider recur$volume;
	/** A widget in the button row: its x offset and width at scale 1. */
	@Unique private final java.util.List<dev.recur.client.BarSlot> recur$slots = new java.util.ArrayList<>();
	@Unique private int recur$rowW;
	/** Make the bar bigger / smaller (one step is one real screen pixel per GUI pixel). */
	@Unique private LoopButton recur$bigger, recur$smaller;
	@Unique private static final int SIZE_BTN = 18;

	@Inject(method = "init", at = @At("TAIL"))
	private void recur$addButtons(CallbackInfo ci) {
		if (!ClientPlayNetworking.canSend(LoopNetworking.ActionPayload.TYPE)) return; // server without Recur
		// A world made without Recur, or watching a Flashback replay: Recur is switched off.
		if (!ClientLoopState.available || dev.recur.client.RecurClient.inReplay()) return;
		recur$slots.clear();
		recur$bigger = recur$smaller = null;
		if (!ClientLoopState.active) {
			int loopW = 196, lengthW = 110, timedW = 150;
			LoopButton loop = new LoopButton(0, 0, loopW, ROW_H, Component.literal("Start the cycle (recommended)"), PixelIcon.HOURGLASS,
				Theme.BLUE, button -> {
					ClientPlayNetworking.send(new LoopNetworking.ActionPayload(LoopNetworking.ACTION_START, ClientSettings.loopMinutes));
					this.minecraft.gui.setScreen(null);
				}).primary();
			loop.setTooltip(Tooltip.create(Component.literal("Recur's main mode: when anyone dies or time runs out, the world resets "
				+ "and your past selves come back as clones. Checkpoints let you pick where runs start.")));
			recur$add(loop, 0, loopW);
			// How long each loop lasts: 10 to 60 minutes, or no limit at the far right.
			LoopSlider length = new LoopSlider(loopW + GAP, 0, lengthW, ROW_H, dev.recur.client.LoopLength.value(ClientSettings.loopMinutes), Theme.BLUE,
				v -> dev.recur.client.LoopLength.label(dev.recur.client.LoopLength.minutes(v)), v -> {}, ClientSettings::save);
			length.setChange(v -> {
				ClientSettings.loopMinutes = dev.recur.client.LoopLength.minutes(v);
				length.setTooltip(dev.recur.client.LoopLength.tooltip(ClientSettings.loopMinutes));
			});
			length.setTooltip(dev.recur.client.LoopLength.tooltip(ClientSettings.loopMinutes));
			recur$add(length, loopW + GAP, lengthW);
			int timedX = loopW + GAP + lengthW + GROUP_GAP;
			LoopButton timed = new LoopButton(timedX, 0, timedW, ROW_H, Component.literal("Start timed resets"), PixelIcon.CLOCK,
				Theme.GREY, button -> {
					ClientPlayNetworking.send(new LoopNetworking.ActionPayload(LoopNetworking.ACTION_START_TIMED, 0));
					this.minecraft.gui.setScreen(null);
				});
			timed.setTooltip(Tooltip.create(Component.literal("Every 2 minutes the whole world resets and your past selves play again as "
				+ "clones, but everyone keeps their items and remains where they are. No checkpoints. "
				+ "Change the time with /loop timed interval <seconds>. Disable the dupe prevention with /loop timed antidupe false.")));
			recur$add(timed, timedX, timedW);
			recur$rowW = timedX + timedW;
			recur$layout();
			return;
		}

		boolean voice = FabricLoader.getInstance().isModLoaded("voicechat");
		int checkpointsW = 92, sliderW = 84;
		// Timed resets have no checkpoints.
		boolean timedMode = ClientLoopState.timedTicks >= 0;
		int total = (timedMode ? 0 : checkpointsW + GAP + ROW_H + GROUP_GAP) + ROW_H + GROUP_GAP + ROW_H
			+ GROUP_GAP + sliderW + (voice ? GROUP_GAP + ROW_H + GAP + sliderW + GAP + ROW_H : 0)
			+ (dev.recur.client.ClientWorldSettings.canEdit() ? GROUP_GAP + ROW_H : 0);
		int x = 0;
		recur$rowW = total;

		// Checkpoints
		if (!timedMode) {
			recur$add(new LoopButton(x, 0, checkpointsW, ROW_H, Component.literal("Checkpoints"), PixelIcon.FLAG, Theme.BLUE,
				button -> this.minecraft.gui.setScreen(new CheckpointScreen(this))), x, checkpointsW);
			x += checkpointsW + GAP;
			LoopButton create = new LoopButton(x, 0, ROW_H, ROW_H, Component.empty(), PixelIcon.FLAG_PLUS, Theme.BLUE,
				button -> dev.recur.client.Thumbnails.createCheckpoint());
			create.setTooltip(Tooltip.create(Component.literal("New checkpoint (" + dev.recur.client.RecurClient.checkpointKeyName() + ")")));
			recur$add(create, x, ROW_H);
			x += ROW_H + GROUP_GAP;
		}

		// How clones look
		LoopButton view = new LoopButton(x, 0, ROW_H, ROW_H, Component.empty(), recur$viewIcon(), Theme.BLUE, button -> {
			ClientLoopState.cloneView = ClientLoopState.cloneView.next();
			((LoopButton) button).setIcon(recur$viewIcon());
			button.setTooltip(recur$viewTooltip());
			ClientSettings.save();
		});
		view.setTooltip(recur$viewTooltip());
		recur$add(view, x, ROW_H);
		x += ROW_H + GROUP_GAP;

		// Clone chat
		LoopButton chat = new LoopButton(x, 0, ROW_H, ROW_H, Component.empty(), recur$chatIcon(), Theme.BLUE, button -> {
			ClientSettings.chatMuted = !ClientSettings.chatMuted;
			((LoopButton) button).setIcon(recur$chatIcon());
			button.setTooltip(recur$chatTooltip());
			ClientSettings.save();
		});
		chat.setTooltip(recur$chatTooltip());
		recur$add(chat, x, ROW_H);
		x += ROW_H + GROUP_GAP;

		// How loud the sounds clones make are
		LoopSlider actions = new LoopSlider(x, 0, sliderW, ROW_H, ClientSettings.actionVolume, Theme.BLUE,
			v -> v <= 0.001 ? "Actions: muted" : "Actions: " + Math.round(v * 100) + "%",
			v -> ClientSettings.actionVolume = (float) v,
			ClientSettings::save);
		actions.setTooltip(Tooltip.create(Component.literal("How loud the sounds your clones make are (footsteps, blocks, getting hurt...)")));
		recur$add(actions, x, sliderW);
		x += sliderW;

		// Clone voices (only with Simple Voice Chat)
		if (voice) {
			x += GROUP_GAP;
			recur$muteButton = new LoopButton(x, 0, ROW_H, ROW_H, Component.empty(), PixelIcon.SPEAKER, Theme.BLUE, button -> {
				ClientSettings.voiceMuted = !ClientSettings.voiceMuted;
				if (!ClientSettings.voiceMuted && ClientSettings.voiceVolume <= 0.001f) ClientSettings.voiceVolume = 1f;
				recur$volume.set(ClientSettings.voiceMuted ? 0 : ClientSettings.voiceVolume);
				recur$refreshVoice();
				ClientSettings.save();
			});
			recur$add(recur$muteButton, x, ROW_H);
			x += ROW_H + GAP;
			recur$volume = new LoopSlider(x, 0, sliderW, ROW_H, ClientSettings.effectiveVoice(), Theme.BLUE,
				v -> v <= 0.001 ? "Voices: muted" : "Voices: " + Math.round(v * 100) + "%",
				v -> {
					ClientSettings.voiceVolume = (float) v;
					ClientSettings.voiceMuted = v <= 0.001;
					recur$refreshVoice();
				},
				ClientSettings::save);
			recur$volume.setTooltip(Tooltip.create(Component.literal("How loud your clones' voices are")));
			recur$add(recur$volume, x, sliderW);
			x += sliderW + GAP;
			LoopButton crowd = new LoopButton(x, 0, ROW_H, ROW_H, Component.empty(), PixelIcon.GRAPH, Theme.BLUE,
				button -> this.minecraft.gui.setScreen(new dev.recur.client.VoiceCurveScreen(this)));
			crowd.setTooltip(Tooltip.create(Component.literal("Clone voices in a crowd: how loud each clone is when many talk at once")));
			recur$add(crowd, x, ROW_H);
			recur$refreshVoice();
			x += ROW_H;
		}

		// Cancelling the loop (operators only), after asking first
		if (dev.recur.client.ClientWorldSettings.canEdit()) {
			x += GROUP_GAP;
			LoopButton cancel = new LoopButton(x, 0, ROW_H, ROW_H, Component.empty(), PixelIcon.CROSS, Theme.RED, button ->
				this.minecraft.gui.setScreen(new dev.recur.client.CancelLoopScreen((Screen) (Object) this)));
			cancel.setTooltip(Tooltip.create(Component.literal("Cancel the loop")));
			recur$add(cancel, x, ROW_H);
		}

		recur$bigger = this.addRenderableWidget(new LoopButton(0, 0, SIZE_BTN, SIZE_BTN, Component.empty(), PixelIcon.UP, Theme.GREY,
			button -> recur$resize(1)));
		recur$bigger.setTooltip(Tooltip.create(Component.literal("Grow")));
		recur$smaller = this.addRenderableWidget(new LoopButton(0, 0, SIZE_BTN, SIZE_BTN, Component.empty(), PixelIcon.DOWN, Theme.GREY,
			button -> recur$resize(-1)));
		recur$smaller.setTooltip(Tooltip.create(Component.literal("Shrink")));
		recur$layout();
	}

	@Unique
	private <T extends net.minecraft.client.gui.components.AbstractWidget> T recur$add(T widget, int baseX, int baseW) {
		recur$slots.add(new dev.recur.client.BarSlot(widget, baseX, baseW));
		return this.addRenderableWidget(widget);
	}

	/** Real screen pixels per GUI pixel. */
	@Unique
	private int recur$gui() { return Math.max(1, this.minecraft.getWindow().getGuiScale()); }

	/** The biggest scale that still fits on screen, in whole steps (see recur$scale). */
	@Unique
	private float recur$maxScale() {
		int gui = recur$gui();
		int content = Math.max(recur$rowW, recur$infoWidth()) + 24 + SIZE_BTN + 8;
		float max = Math.min(2.5f, Math.min(this.height * 0.5f / BAR_H, (this.width - 16f) / content));
		return Math.max(1, (int) Math.floor(max * gui + 1e-3f)) / (float) gui;
	}

	@Unique
	private float recur$minScale() {
		int gui = recur$gui();
		return Math.min(recur$maxScale(), Math.max(1, (int) Math.ceil(0.5f * gui - 1e-3f)) / (float) gui);
	}

	/**
	 * The bar's scale, kept small enough to fit on screen, and always a whole number of real screen pixels per GUI pixel
	 * so its pixel art, edges and text stay sharp and square.
	 */
	@Unique
	private float recur$scale() {
		int gui = recur$gui();
		float s = Math.max(recur$minScale(), Math.min(recur$maxScale(), ClientSettings.barScale));
		return Math.max(1, Math.round(s * gui)) / (float) gui;
	}

	/** One step bigger (+1) or smaller (-1). */
	@Unique
	private void recur$resize(int steps) {
		ClientSettings.barScale = recur$scale() + steps / (float) recur$gui();
		ClientSettings.barScale = recur$scale();
		recur$layout();
		ClientSettings.save();
	}

	/** Places and sizes every bar widget for the current scale. */
	@Unique
	private void recur$layout() {
		float scale = recur$scale();
		int rowH = Math.round(ROW_H * scale);
		int rowY = this.height - rowH - Math.round(5 * scale);
		int left = Math.round((this.width - recur$rowW * scale) / 2f);
		for (dev.recur.client.BarSlot slot : recur$slots) {
			slot.widget().setRectangle(Math.round(slot.baseW() * scale), rowH, left + Math.round(slot.baseX() * scale), rowY);
			if (slot.widget() instanceof LoopButton button) button.scale(scale);
			else if (slot.widget() instanceof LoopSlider slider) slider.scale(scale);
		}
		if (recur$bigger != null) {
			// Stacked up/down on the left of the bar'scale contents.
			int top = this.height - Math.round(BAR_H * scale);
			int contentLeft = Math.round((this.width - Math.max(recur$rowW, recur$infoWidth()) * scale) / 2f);
			int size = Math.round(SIZE_BTN * scale), x = contentLeft - size - Math.round(8 * scale);
			recur$bigger.setRectangle(size, size, x, top + Math.round(4 * scale));
			recur$smaller.setRectangle(size, size, x, top + Math.round((6 + SIZE_BTN) * scale));
			recur$bigger.scale(scale);
			recur$smaller.scale(scale);
			recur$bigger.active = scale < recur$maxScale() - 1e-4f;
			recur$smaller.active = scale > recur$minScale() + 1e-4f;
		}
	}

	@Unique
	private void recur$refreshVoice() {
		if (recur$muteButton == null) return;
		recur$muteButton.setIcon(ClientSettings.voiceMuted ? PixelIcon.SPEAKER_OFF : PixelIcon.SPEAKER);
		recur$muteButton.setTooltip(Tooltip.create(Component.literal(ClientSettings.voiceMuted ? "Unmute clone voices" : "Mute clone voices")));
	}

	@Unique
	private static PixelIcon recur$viewIcon() {
		return switch (ClientLoopState.cloneView) {
			case SHOWN -> PixelIcon.EYE;
			case TRANSPARENT -> PixelIcon.GHOST;
			case HIDDEN -> PixelIcon.EYE_OFF;
			case HIDDEN_NO_TAGS -> PixelIcon.EYE_CLOSED;
		};
	}

	@Unique
	private static Tooltip recur$viewTooltip() {
		return Tooltip.create(Component.literal("Clones: " + ClientLoopState.cloneView.label + "\n§7Click to change"));
	}

	@Unique
	private static PixelIcon recur$chatIcon() { return ClientSettings.chatMuted ? PixelIcon.CHAT_OFF : PixelIcon.CHAT; }

	@Unique
	private static Tooltip recur$chatTooltip() {
		return Tooltip.create(Component.literal(ClientSettings.chatMuted ? "Clone chat: muted\n§7Click to show it" : "Clone chat: shown\n§7Click to mute it"));
	}

	/** Drawn before the widgets: the bar's background and the info line. */
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void recur$drawBar(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		// No bar at all in a world made without Recur, on a server without it, or in a Flashback replay.
		if (!ClientPlayNetworking.canSend(LoopNetworking.ActionPayload.TYPE)) return;
		if (!ClientLoopState.available || dev.recur.client.RecurClient.inReplay()) return;
		float scale = recur$scale();
		int top = this.height - Math.round((ClientLoopState.active ? BAR_H : ROW_H + 10) * scale);
		// The bar, with a faint line along its top edge.
		g.fill(0, top, this.width, this.height, 0xC8000000 | Theme.SHADE_RGB);
		g.fill(0, top, this.width, top + 1, 0x30FFFFFF);
		if (!ClientLoopState.active) return;

		String[] texts = recur$infoTexts();
		int chipGap = 14, total = recur$infoWidth();
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((this.width - total * scale) / 2f, top + 6 * scale);
		pose.scale(scale, scale);
		int x = 0, y = 0;
		for (int i = 0; i < texts.length; i++) {
			if (i > 0) {
				g.fill(x + chipGap / 2 - 1, y + 3, x + chipGap / 2 + 1, y + 5, Theme.TEXT_OFF);
				x += chipGap;
			}
			CHIP_ICONS[i].draw(g, x, y, 1, 0, 0, 1f);
			x += 12;
			g.text(this.font, texts[i], x, y, i == 0 ? Theme.TITLE : Theme.TEXT);
			x += this.font.width(texts[i]);
		}
		pose.popMatrix();
	}

	@Unique private static final PixelIcon[] CHIP_ICONS = {PixelIcon.HOURGLASS, PixelIcon.CLOCK, PixelIcon.GHOST, PixelIcon.FLAG};

	/** Width of the info line at scale 1 (0 when the loop hasn't started). */
	@Unique
	private int recur$infoWidth() {
		if (!ClientLoopState.active) return 0;
		int total = 0;
		String[] texts = recur$infoTexts();
		for (int i = 0; i < texts.length; i++) total += 12 + this.font.width(texts[i]) + (i > 0 ? 14 : 0);
		return total;
	}

	@Unique
	private String[] recur$infoTexts() {
		String next = "Loop start";
		for (var c : ClientLoopState.checkpoints) if (c.id() == ClientLoopState.effectiveId) next = c.name();
		String clones = ClientLoopState.totalClones == ClientLoopState.myClones
			? ClientLoopState.myClones + (ClientLoopState.myClones == 1 ? " clone" : " clones")
			: ClientLoopState.myClones + " yours, " + ClientLoopState.totalClones + " total";
		String[] texts = {"Loop " + ClientLoopState.runNumber, ClientLoopState.timeLeft(), clones, "Next: " + next};
		for (int i = 0; i < texts.length; i++) {
			int maxW = 110;
			if (this.font.width(texts[i]) > maxW) {
				while (texts[i].length() > 1 && this.font.width(texts[i] + "...") > maxW) texts[i] = texts[i].substring(0, texts[i].length() - 1);
				texts[i] += "...";
			}
		}
		return texts;
	}
}
