package dev.recur.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Shown the first time a player joins a Recur world: a few short pages on how the loop works. */
public class TutorialScreen extends PromptScreen {
	private record Page(PixelIcon icon, String title, String text) {}

	private static final int PANEL_WIDTH = 320;
	private static final int PADDING = 12;

	private final Page[] pages;
	private int page;

	public TutorialScreen() {
		super(Component.literal("How Recur works"));
		String key = RecurClient.checkpointKeyName();
		pages = new Page[] {
			new Page(PixelIcon.HOURGLASS, "Welcome to Recur",
				"When enabled, the world will become a time loop. Please take a minute to read this short tutorial on how this mod works."),
			new Page(PixelIcon.CLOCK, "The loop",
				"Once the loop is started from the pause menu, an adjustable timer counts down, which is in the top right of your screen. "
					+ "When it runs out, or when anyone dies, "
					+ "the whole world resets and everyone goes back to where the run started."),
			new Page(PixelIcon.GHOST, "Your clones",
				"Everything you do in a run is saved. In the next run a clone of you does it all again, including "
					+ "talking, so you can plan ahead and work together with your past selves. If you crouch, you can kill another clone. "
					+ "In future loops, the clone will play until it's killed by your past self. If you kill your murderous past self, "
					+ "then it will again play out in full."),
			new Page(PixelIcon.FLAG_PLUS, "Checkpoints",
				"Press " + key + " to save a checkpoint where you are, or use the flag button in the pause menu. "
					+ "You'll be asked for a name, and pressing Enter keeps the suggested one. The key can be changed in "
					+ "Options > Controls > Key Binds, under Recur."),
			new Page(PixelIcon.FLAG, "Picking where to start",
				"Open Checkpoints in the pause menu and click one to start the next run there. With friends, everyone has to "
					+ "pick the same one, otherwise the run starts at the loop start. Starred checkpoints stay at the top, and can be ordered separately."),
			new Page(PixelIcon.EYE, "Your settings",
				"The bar at the bottom of the pause menu lets you show or hide clones, mute their chat and set how loud "
					+ "their voices are. Have fun!"),
		};
	}

	private int panelX() { return (this.width - PANEL_WIDTH) / 2; }

	/** Tall enough for the title row, the current page's text and the buttons. */
	private int panelHeight() {
		int lines = this.font.split(Component.literal(pages[page].text()), PANEL_WIDTH - PADDING * 2).size();
		return PADDING + 26 + lines * this.font.lineHeight + 12 + 20 + PADDING;
	}

	private int panelY() { return (this.height - panelHeight()) / 2; }

	@Override
	protected void init() {
		int right = panelX() + PANEL_WIDTH - PADDING, bottom = panelY() + panelHeight() - PADDING - 20;
		boolean last = page == pages.length - 1;
		this.addRenderableWidget(new LoopButton(right - 96, bottom, 96, 20, Component.literal(last ? "Let's go" : "Next"),
			last ? PixelIcon.CHECK : PixelIcon.RIGHT, Theme.BLUE, button -> {
				if (last) onClose(); else { page++; rebuildWidgets(); }
			}).primary());
		LoopButton back = new LoopButton(right - 96 - 4 - 64, bottom, 64, 20, Component.literal("Back"), PixelIcon.LEFT, Theme.GREY,
			button -> { page--; rebuildWidgets(); });
		back.active = page > 0;
		this.addRenderableWidget(back);
		if (!last) this.addRenderableWidget(new LoopButton(panelX() + PADDING, bottom, 56, 20, Component.literal("Skip"), PixelIcon.CROSS,
			Theme.GREY, button -> onClose()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = panelX(), y = panelY();
		Theme.panel(g, x, y, PANEL_WIDTH, panelHeight());
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		Page p = pages[page];
		p.icon().draw(g, x + PADDING, y + PADDING, 2, 0, 0, 1f);
		g.text(this.font, p.title(), x + PADDING + 22, y + PADDING + 4, Theme.TITLE);
		String counter = (page + 1) + " / " + pages.length;
		g.text(this.font, counter, x + PANEL_WIDTH - PADDING - this.font.width(counter), y + PADDING + 4, Theme.TEXT_DIM);
		g.textWithWordWrap(this.font, Component.literal(p.text()), x + PADDING, y + PADDING + 26, PANEL_WIDTH - PADDING * 2, Theme.TEXT);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
