package dev.recur.client;

import dev.recur.LoopNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every checkpoint with a little picture of where it is, as many as fit on the screen. Click one to vote for it. The
 * highlighted one is where the next run starts (everyone agrees), and a dashed outline means some players want it.
 * The loop start is always the first row. Starred checkpoints come next and can be dragged by the handle on the left
 * into any order.
 */
public class CheckpointScreen extends Screen {
	private static final int ROW_H = 22, ROW_STEP = 25, TOP = 50, GRIP_W = 12;
	private final Screen parent;
	private int page;
	private String lastShown = "";

	/** Rows on the current page: where each starred row's drag handle is. */
	private record Grip(int id, int x, int y) {}
	private final List<Grip> grips = new ArrayList<>();

	/** While dragging (or until the server confirms the new order): the starred checkpoints, top first. */
	private List<Integer> dragOrder;
	private int dragging = Integer.MIN_VALUE;

	public CheckpointScreen(Screen parent) {
		super(Component.literal("Checkpoints"));
		this.parent = parent;
	}

	private int rowsPerPage() {
		return Math.max(2, (this.height - 52 - 6 - TOP) / ROW_STEP);
	}

	/** Checkpoint rows per page. The loop start takes the first row of every page. */
	private int listRows() {
		return rowsPerPage() - 1;
	}

	private static LoopNetworking.CheckpointInfo loopStart() {
		return new LoopNetworking.CheckpointInfo(-1, "Loop start", "", 0, "", 0, 0, 0, ClientLoopState.startVotes, 0);
	}

	/** The checkpoints in list order: starred ones (in their order), then everything else. */
	private List<LoopNetworking.CheckpointInfo> entries() {
		List<LoopNetworking.CheckpointInfo> starred = new ArrayList<>(), rest = new ArrayList<>();
		for (var c : ClientLoopState.checkpoints) (c.star() > 0 ? starred : rest).add(c);
		if (dragOrder != null) {
			List<LoopNetworking.CheckpointInfo> ordered = new ArrayList<>();
			for (int id : dragOrder) for (var c : starred) if (c.id() == id) ordered.add(c);
			for (var c : starred) if (!ordered.contains(c)) ordered.add(c);
			starred = ordered;
		}
		List<LoopNetworking.CheckpointInfo> all = new ArrayList<>(starred);
		all.addAll(rest);
		return all;
	}

	private List<Integer> starredIds() {
		List<Integer> ids = new ArrayList<>();
		for (var c : entries()) if (c.star() > 0) ids.add(c.id());
		return ids;
	}

	@Override
	protected void init() {
		lastShown = shownState();
		grips.clear();
		int cx = this.width / 2, y = TOP, rows = listRows();
		List<LoopNetworking.CheckpointInfo> all = entries();

		int pages = Math.max(1, (all.size() + rows - 1) / rows);
		page = Math.min(page, pages - 1);
		List<LoopNetworking.CheckpointInfo> shown = new ArrayList<>();
		shown.add(loopStart());
		shown.addAll(all.subList(Math.min(all.size(), page * rows), Math.min(all.size(), (page + 1) * rows)));
		for (LoopNetworking.CheckpointInfo c : shown) {
			boolean active = c.id() == ClientLoopState.effectiveId;
			boolean pending = !active && c.id() != -1 && c.votes() > 0;
			boolean start = c.id() == -1;
			boolean starred = c.star() > 0;
			int rowX = cx - 190 + GRIP_W + 2;

			LoopButton row = new LoopButton(rowX, y, start ? cx + 190 - rowX : cx + 114 - rowX, ROW_H, Component.literal(c.name()),
				start ? PixelIcon.CLOCK : PixelIcon.FLAG, active ? Theme.GREEN : Theme.BLUE,
				button -> send(LoopNetworking.ACTION_VOTE, c.id()))
				.selected(active || c.id() == dragging)
				.pending(pending, pending ? c.votes() + "/" + ClientLoopState.players : null);
			var thumb = Thumbnails.get(c.id()); // the loop start's picture is id -1
			if (thumb != null) row.picture(thumb, Thumbnails.width(), Thumbnails.height());
			this.addRenderableWidget(row);

			if (!start) {
				LoopButton star = new LoopButton(cx + 118, y, 22, ROW_H, Component.empty(), starred ? PixelIcon.STAR : PixelIcon.STAR_OFF,
					Theme.YELLOW, button -> send(LoopNetworking.ACTION_STAR, c.id()));
				star.setTooltip(Tooltip.create(Component.literal(starred ? "Unstar" : "Star: keep it at the top")));
				this.addRenderableWidget(star);
				this.addRenderableWidget(new LoopButton(cx + 142, y, 22, ROW_H, Component.empty(), PixelIcon.PENCIL, Theme.GREY,
					button -> this.minecraft.gui.setScreen(new RenameScreen(this, c.id(), c.name()))));
				this.addRenderableWidget(new LoopButton(cx + 166, y, 22, ROW_H, Component.empty(), PixelIcon.CROSS, Theme.RED,
					button -> send(LoopNetworking.ACTION_DELETE, c.id())));
			}
			if (starred) grips.add(new Grip(c.id(), cx - 190, y));
			y += ROW_STEP;
		}

		int bottom = this.height - 52;
		LoopButton prev = new LoopButton(cx - 150, bottom, 40, 20, Component.empty(), PixelIcon.LEFT, Theme.GREY,
			button -> { page = Math.max(0, page - 1); rebuildWidgets(); });
		prev.active = page > 0;
		this.addRenderableWidget(prev);
		this.addRenderableWidget(new LoopButton(cx - 105, bottom, 210, 20, Component.literal("Create checkpoint here"), PixelIcon.FLAG_PLUS,
			Theme.BLUE, button -> Thumbnails.createCheckpoint()));
		LoopButton next = new LoopButton(cx + 110, bottom, 40, 20, Component.empty(), PixelIcon.RIGHT, Theme.GREY,
			button -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); });
		next.active = page < pages - 1;
		this.addRenderableWidget(next);
		this.addRenderableWidget(new LoopButton(cx - 100, bottom + 24, 200, 20, Component.literal("Done"), PixelIcon.CHECK, Theme.GREY,
			button -> this.minecraft.gui.setScreen(parent)));
	}

	private void send(int action, int id) {
		ClientPlayNetworking.send(new LoopNetworking.ActionPayload(action, id));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			for (Grip g : grips) {
				if (event.x() >= g.x() && event.x() < g.x() + GRIP_W && event.y() >= g.y() && event.y() < g.y() + ROW_H) {
					dragging = g.id();
					dragOrder = starredIds();
					rebuildWidgets();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging == Integer.MIN_VALUE) return super.mouseDragged(event, dx, dy);
		int from = dragOrder.indexOf(dragging);
		if (from < 0) return true;
		// Starred checkpoints are the first entries of the list, so the row under the mouse is the new position.
		// The loop start is the first row, so the starred rows begin one row further down.
		int to = page * listRows() + (int) Math.floor((event.y() - TOP - ROW_STEP) / ROW_STEP);
		to = Math.max(0, Math.min(dragOrder.size() - 1, to));
		if (to != from) {
			dragOrder.add(to, dragOrder.remove(from));
			rebuildWidgets();
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging == Integer.MIN_VALUE) return super.mouseReleased(event);
		dragging = Integer.MIN_VALUE;
		ClientPlayNetworking.send(new LoopNetworking.StarOrderPayload(new ArrayList<>(dragOrder)));
		rebuildWidgets(); // dragOrder stays until the server sends the new order back
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (vertical != 0 && dragging == Integer.MIN_VALUE) {
			int pages = Math.max(1, (entries().size() + listRows() - 1) / listRows());
			int to = Math.max(0, Math.min(pages - 1, page + (vertical < 0 ? 1 : -1)));
			if (to != page) { page = to; rebuildWidgets(); }
			return true;
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	@Override
	public void tick() {
		super.tick();
		// Rebuild only when what the list shows actually changed (rebuilding resets the hover state).
		if (dragging == Integer.MIN_VALUE && !shownState().equals(lastShown)) {
			dragOrder = null; // the server's order is the real one now
			rebuildWidgets();
		}
	}

	private String shownState() {
		StringBuilder s = new StringBuilder();
		for (var c : ClientLoopState.checkpoints) s.append(c.id()).append(':').append(c.name()).append(':').append(c.votes()).append(':')
			.append(c.star()).append(':').append(Thumbnails.get(c.id()) != null).append(';');
		return s + "|" + ClientLoopState.effectiveId + "|" + ClientLoopState.players + "|" + ClientLoopState.startVotes
			+ "|" + (Thumbnails.get(-1) != null);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.width / 2 - 150;
		graphics.text(this.font, "Where should the next run start?", left, 14, Theme.TITLE);
		graphics.text(this.font, ClientLoopState.players > 1
				? "Checkpoints must be mutual."
				: "Click a checkpoint to start the next run there.",
			left, 28, Theme.TEXT_DIM);
		// Each starred row has a drag handle drawn as three short lines.
		for (Grip g : grips) {
			boolean hot = g.id() == dragging || (mouseX >= g.x() && mouseX < g.x() + GRIP_W && mouseY >= g.y() && mouseY < g.y() + ROW_H);
			int line = hot ? Theme.YELLOW : Theme.TEXT_DIM;
			graphics.fill(g.x(), g.y(), g.x() + GRIP_W, g.y() + ROW_H, hot ? Theme.PANEL_LIT : Theme.PANEL);
			int cy = g.y() + ROW_H / 2;
			for (int i = -3; i <= 3; i += 3) graphics.fill(g.x() + 3, cy + i, g.x() + GRIP_W - 3, cy + i + 1, line);
			if (hot) graphics.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.RESIZE_NS);
		}
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
