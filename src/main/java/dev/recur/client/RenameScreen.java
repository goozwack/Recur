package dev.recur.client;

import dev.recur.LoopNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Type a name for a checkpoint. Used to rename one, and right after creating one ("naming"): then the box starts empty
 * with the default name as a hint, and Enter (or Esc) keeps that default. Either way the server then asks everyone
 * else whether they want the new checkpoint.
 */
public class RenameScreen extends Screen {
	private final Screen parent;
	private final int id;
	private final String current;
	private final boolean naming;
	private EditBox box;
	private boolean sent;

	public RenameScreen(Screen parent, int id, String current) { this(parent, id, current, false); }

	public RenameScreen(Screen parent, int id, String current, boolean naming) {
		super(Component.literal(naming ? "Name your checkpoint" : "Rename checkpoint"));
		this.parent = parent;
		this.id = id;
		this.current = current;
		this.naming = naming;
	}

	private static final int PANEL_WIDTH = 240;
	private static final int PADDING = 10;
	private static final int BUTTON_WIDTH = 64;

	/** Title, the name box with its button, and (while naming) a hint line below. */
	private int panelHeight() { return PADDING + 14 + 20 + (naming ? 16 : 0) + PADDING; }

	private int panelX() { return (this.width - PANEL_WIDTH) / 2; }

	private int panelY() { return (this.height - panelHeight()) / 2; }

	@Override
	protected void init() {
		int x = panelX() + PADDING, y = panelY() + PADDING, right = panelX() + PANEL_WIDTH - PADDING;
		// The name box, with the Done / Save button on its right.
		box = new EditBox(this.font, x, y + 14, right - x - BUTTON_WIDTH - 4, 20, Component.literal("Name"));
		box.setMaxLength(32);
		if (naming) box.setHint(Component.literal(current));
		else box.setValue(current);
		this.addRenderableWidget(box);
		this.setInitialFocus(box);
		this.addRenderableWidget(new LoopButton(right - BUTTON_WIDTH, y + 14, BUTTON_WIDTH, 20, Component.literal(naming ? "Done" : "Save"),
			PixelIcon.CHECK, Theme.BLUE, button -> save()).primary());
	}

	private void save() {
		String name = box.getValue().strip();
		// While naming, an empty box keeps the default name (and still tells the server the checkpoint is ready).
		if (!name.isEmpty() || naming) send(name);
		this.minecraft.gui.setScreen(parent);
	}

	private void cancel() {
		if (naming) send("");
		this.minecraft.gui.setScreen(parent);
	}

	private void send(String name) {
		if (sent) return;
		sent = true;
		ClientPlayNetworking.send(new LoopNetworking.RenamePayload(id, name));
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) { save(); return true; }
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Theme.panel(graphics, panelX(), panelY(), PANEL_WIDTH, panelHeight());
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, this.title, panelX() + PADDING, panelY() + PADDING, Theme.TITLE);
		if (naming) graphics.text(this.font, "Enter keeps \"" + current + "\"", panelX() + PADDING, panelY() + PADDING + 40, Theme.TEXT_DIM);
	}

	@Override
	public void onClose() { cancel(); }

	@Override
	public boolean isPauseScreen() { return false; }
}
