package dev.recur.client;

import dev.recur.LoopNetworking;
import dev.recur.ServerSettings;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Recur's settings, opened from Mod Menu. In a world it shows that world's settings (only operators can change them),
 * and a change takes effect right away; from the title screen it shows the ones new worlds start with. Loop length is
 * yours alone.
 */
public class RecurConfigScreen extends Screen {
	private static final int[] TIMED_SECONDS = { 10, 20, 30, 45, 60, 90, 120, 180, 240, 300, 420, 600, 900, 1200, 1800 };
	private static final int W = 150, H = 20, GAP = 8, ROW = 24;

	private final Screen parent;
	private final boolean inWorld;
	private final boolean editable;
	private final ServerSettings.Values start;
	private int loadPercent, timedSeconds;
	private boolean smartReset, antiDupe, clearBlocks, cloneLoot, loopAntiDupe;
	private final List<AbstractWidget> worldWidgets = new ArrayList<>();
	private int worldHeaderY;
	/** In a world whose settings haven't arrived yet: they're asked for, and the screen opens again with them. */
	private final boolean waiting;
	/** What the world has now, and how long the settings shown have been as they are (changes go out once they settle). */
	private ServerSettings.Values sent, shown;
	private int settled;

	public RecurConfigScreen(Screen parent) {
		super(Component.literal("Recur"));
		this.parent = parent;
		var mc = net.minecraft.client.Minecraft.getInstance();
		this.inWorld = ClientWorldSettings.known() || mc.level != null;
		this.waiting = inWorld && !ClientWorldSettings.known();
		if (waiting && ClientPlayNetworking.canSend(LoopNetworking.SettingsRequestPayload.TYPE))
			ClientPlayNetworking.send(new LoopNetworking.SettingsRequestPayload());
		this.editable = !inWorld || (!waiting && ClientWorldSettings.canEdit());
		this.start = inWorld && !waiting ? ClientWorldSettings.values() : ServerSettings.defaults();
		this.sent = start;
		loadPercent = start.loadPercent();
		smartReset = start.smartReset();
		timedSeconds = start.timedSeconds();
		antiDupe = start.antiDupe();
		clearBlocks = start.clearBlocks();
		cloneLoot = start.cloneLoot();
		loopAntiDupe = start.loopAntiDupe();
	}

	@Override
	protected void init() {
		worldWidgets.clear();
		int left = this.width / 2 - W - GAP / 2, right = this.width / 2 + GAP / 2;
		int y = 48;
		worldHeaderY = y - 14;

		worldWidgets.add(addRenderableWidget(new Slider(left, y, loadPercent / 100.0,
			v -> "Load before a run: " + step5(v) + "%", v -> loadPercent = step5(v),
			"How much of the area around each player has to load before a run starts. Higher means fewer pop-ins, a longer wait.")));
		worldWidgets.add(addRenderableWidget(toggle(right, y, "Smart reset", smartReset, v -> smartReset = v,
			"Put back only the chunks that changed, instead of unloading and reloading the whole world. Much faster.")));
		y += ROW;
		worldWidgets.add(addRenderableWidget(new Slider(left, y, timedIndex(timedSeconds) / (double) (TIMED_SECONDS.length - 1),
			v -> "Timed resets: every " + describe(TIMED_SECONDS[timedStep(v)]), v -> timedSeconds = TIMED_SECONDS[timedStep(v)],
			"In timed reset mode, how often the whole world resets.")));
		worldWidgets.add(addRenderableWidget(toggle(right, y, "Timed anti-dupe", antiDupe, v -> antiDupe = v,
			"Timed resets: what clones make, drop or take can't be mined, picked up or taken, so nothing gets duplicated.")));
		y += ROW;
		worldWidgets.add(addRenderableWidget(toggle(left, y, "Clear blocks at players", clearBlocks, v -> clearBlocks = v,
			"Timed resets: after a reset, solid blocks where players stand are cleared so nobody gets stuck.")));
		y += ROW;
		worldWidgets.add(addRenderableWidget(toggle(left, y, "Killed clones drop items", cloneLoot, v -> cloneLoot = v,
			"Killing a clone drops everything it carried, and its experience. Not while anti-dupe rules are on: in timed resets that's Timed anti-dupe (on by default).")));
		worldWidgets.add(addRenderableWidget(toggle(right, y, "Loop anti-dupe", loopAntiDupe, v -> loopAntiDupe = v,
			"Normal loops: the same anti-dupe rules as timed resets. What clones make, drop or take can't be mined, picked up or taken.")));
		if (!editable) for (AbstractWidget w : worldWidgets) {
			w.active = false;
			w.setTooltip(Tooltip.create(Component.literal(waiting ? "Getting this world's settings..." : "Only operators can change these.")));
		}

		y += ROW;
		addRenderableWidget(toggle(left, y, "Clones fade in", ClientSettings.cloneFadeIn, v -> ClientSettings.cloneFadeIn = v,
			"When a run starts, clones appear after a moment and fade in. Off: they're there right away."));
		addRenderableWidget(toggle(right, y, "Remember settings", ClientSettings.rememberSettings, v -> ClientSettings.rememberSettings = v,
			"World settings you change in a world are also what your new worlds start with."));

		// the loop length is used when the loop starts, so it's in the world section too
		Slider length = new Slider(right, y - 2 * ROW, LoopLength.value(ClientSettings.loopMinutes),
			v -> LoopLength.label(LoopLength.minutes(v)), v -> ClientSettings.loopMinutes = LoopLength.minutes(v), null);
		length.setChange(v -> {
			ClientSettings.loopMinutes = LoopLength.minutes(v);
			length.setTooltip(LoopLength.tooltip(ClientSettings.loopMinutes));
		});
		length.setTooltip(LoopLength.tooltip(ClientSettings.loopMinutes));
		addRenderableWidget(length);

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(this.width / 2 - 100, this.height - 28, 200, H).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		int left = this.width / 2 - W - GAP / 2;
		String world = inWorld ? (editable ? "This world (everyone)" : "This world (everyone) - operators only") : "New worlds start with";
		graphics.text(this.font, world, left, worldHeaderY, 0xFFA0A0A0);
	}

	public boolean waiting() { return waiting; }

	public RecurConfigScreen reopen() { return new RecurConfigScreen(parent); }

	private ServerSettings.Values values() {
		return new ServerSettings.Values(loadPercent, smartReset, timedSeconds, antiDupe, clearBlocks, cloneLoot, loopAntiDupe);
	}

	/** A world setting that was changed goes to the world as soon as it stops changing (a slider being dragged). */
	@Override
	public void tick() {
		super.tick();
		if (!inWorld || !editable) return;
		ServerSettings.Values v = values();
		if (!v.equals(shown)) { shown = v; settled = 0; return; }
		if (++settled >= 5) send(v);
	}

	private void send(ServerSettings.Values v) {
		if (v.equals(sent) || !ClientPlayNetworking.canSend(LoopNetworking.SettingsPayload.TYPE)) return;
		ClientPlayNetworking.send(new LoopNetworking.SettingsPayload(v, true));
		sent = v;
		if (ClientSettings.rememberSettings) ServerSettings.saveDefaults(v); // new worlds start with them too
	}

	@Override
	public void onClose() {
		ClientSettings.save();
		if (editable) {
			if (!inWorld) ServerSettings.saveDefaults(values());
			else send(values());
		}
		this.minecraft.gui.setScreen(parent);
	}

	private static CycleButton<Boolean> toggle(int x, int y, String name, boolean value, java.util.function.Consumer<Boolean> set, String tip) {
		CycleButton<Boolean> b = CycleButton.onOffBuilder(value).create(x, y, W, H, Component.literal(name), (btn, v) -> set.accept(v));
		b.setTooltip(Tooltip.create(Component.literal(tip)));
		return b;
	}

	private static int step5(double v) { return (int) Math.round(v * 20) * 5; }

	private static int timedStep(double v) { return (int) Math.round(v * (TIMED_SECONDS.length - 1)); }

	private static int timedIndex(int seconds) {
		int best = 0;
		for (int i = 0; i < TIMED_SECONDS.length; i++) if (Math.abs(TIMED_SECONDS[i] - seconds) < Math.abs(TIMED_SECONDS[best] - seconds)) best = i;
		return best;
	}

	private static String describe(int seconds) {
		if (seconds < 60) return seconds + " s";
		return seconds % 60 == 0 ? seconds / 60 + " min" : seconds / 60 + " min " + seconds % 60 + " s";
	}

	/** A plain vanilla slider. */
	private static final class Slider extends AbstractSliderButton {
		private final DoubleFunction<String> label;
		private DoubleConsumer change;

		Slider(int x, int y, double value, DoubleFunction<String> label, DoubleConsumer change, String tip) {
			super(x, y, W, H, Component.literal(label.apply(value)), value);
			this.label = label;
			this.change = change;
			if (tip != null) setTooltip(Tooltip.create(Component.literal(tip)));
		}

		void setChange(DoubleConsumer change) { this.change = change; }

		@Override protected void updateMessage() { setMessage(Component.literal(label.apply(value))); }

		@Override protected void applyValue() { change.accept(value); }
	}
}
