package dev.recur.client;

import dev.recur.LoopNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Someone made a checkpoint, and this asks whether the next run should start there too. */
public class AcceptCheckpointScreen extends PromptScreen {
	/** The thumbnail is drawn at three times its size. */
	private static final int PICTURE_HEIGHT = 81;
	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 10;

	private final int id;
	private final String name, creator;

	public AcceptCheckpointScreen(int id, String name, String creator) {
		super(Component.literal("New checkpoint"));
		this.id = id;
		this.name = name;
		this.creator = creator;
	}

	private int panelHeight() { return PADDING + 24 + PICTURE_HEIGHT + 8 + 20 + PADDING; }

	private int panelX() { return (this.width - PANEL_WIDTH) / 2; }

	private int panelY() { return (this.height - panelHeight()) / 2; }

	@Override
	protected void init() {
		int right = panelX() + PANEL_WIDTH - PADDING, buttonY = panelY() + panelHeight() - PADDING - 20;
		this.addRenderableWidget(new LoopButton(right - 100, buttonY, 100, 20, Component.literal("Start there"), PixelIcon.CHECK, Theme.BLUE,
			button -> {
				ClientPlayNetworking.send(new LoopNetworking.ActionPayload(LoopNetworking.ACTION_VOTE, id));
				onClose();
			}).primary());
		this.addRenderableWidget(new LoopButton(right - 100 - 4 - 76, buttonY, 76, 20, Component.literal("Not now"), PixelIcon.CROSS, Theme.GREY,
			button -> onClose()));
	}

	@Override
	public void tick() {
		super.tick();
		// If it was deleted or already picked in the meantime, there's nothing left to ask.
		boolean exists = false;
		for (var c : ClientLoopState.checkpoints) if (c.id() == id) exists = true;
		if (!exists || ClientLoopState.myVote == id || !ClientLoopState.active) onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = panelX(), y = panelY();
		Theme.panel(g, x, y, PANEL_WIDTH, panelHeight());
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, creator + " made the checkpoint \"" + name + "\"", x + PADDING, y + PADDING, Theme.TITLE);
		g.text(this.font, "Should the next run start there?", x + PADDING, y + PADDING + 12, Theme.TEXT);
		var thumb = Thumbnails.get(id);
		int pictureY = y + PADDING + 24;
		if (thumb != null) {
			int pictureWidth = PICTURE_HEIGHT * Thumbnails.width() / Thumbnails.height();
			g.fill(x + PADDING - 1, pictureY - 1, x + PADDING + pictureWidth + 1, pictureY + PICTURE_HEIGHT + 1, Theme.PANEL_EDGE);
			g.blit(RenderPipelines.GUI_TEXTURED, thumb, x + PADDING, pictureY, 0f, 0f, pictureWidth, PICTURE_HEIGHT,
				Thumbnails.width(), Thumbnails.height(), Thumbnails.width(), Thumbnails.height());
		}
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
