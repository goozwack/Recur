package dev.recur.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Edits how loud clone voices are when many clones talk over each other. The graph goes from the oldest clone talking
 * (left) to the newest (right), with the volume going up. The slider at the top sets how many clones have to talk at
 * once before the graph is used. Points and their handles can be dragged, a double-click adds a point, and a
 * right-click removes one.
 */
public class VoiceCurveScreen extends Screen {
	private static final int MIN_CROWD = 2, MAX_CROWD = 16;
	private static final int SIDE = 40, TOP = 62, BOTTOM = 74;
	private static final int POINT_SIZE = 6, HANDLE_SIZE = 4, GRAB_DISTANCE = 5;

	private final Screen parent;
	/** The curve being edited. ClientSettings gets a copy whenever it changes. */
	private final VoiceCurve curve;
	/** What's being dragged: a point, or one end of a point's handle (+1 = the right end, -1 = the left end). */
	private int dragPoint = -1, dragHandle;

	public VoiceCurveScreen(Screen parent) {
		super(Component.literal("Clone voices in a crowd"));
		this.parent = parent;
		this.curve = ClientSettings.voiceCurve.copy();
	}

	private int graphX() { return SIDE; }
	private int graphY() { return TOP; }
	private int graphWidth() { return this.width - SIDE * 2; }
	private int graphHeight() { return Math.max(40, this.height - TOP - BOTTOM); }

	private float screenX(float x) { return graphX() + x * graphWidth(); }
	private float screenY(float y) { return graphY() + (1 - y) * graphHeight(); }
	private float curveX(double sx) { return (float) ((sx - graphX()) / graphWidth()); }
	private float curveY(double sy) { return (float) (1 - (sy - graphY()) / graphHeight()); }

	@Override
	protected void init() {
		int sliderWidth = Math.min(260, this.width - SIDE * 2);
		LoopSlider crowd = new LoopSlider(SIDE, 30, sliderWidth, 20, (ClientSettings.voiceCrowd - MIN_CROWD) / (double) (MAX_CROWD - MIN_CROWD), Theme.BLUE,
			v -> "Clones talking at once: " + crowdFrom(v),
			v -> ClientSettings.voiceCrowd = crowdFrom(v),
			ClientSettings::save);
		crowd.setTooltip(Tooltip.create(Component.literal("The graph is used once at least this many clones talk over each other")));
		this.addRenderableWidget(crowd);

		int y = this.height - BOTTOM + 30, x = SIDE;
		x = template(x, y, 70, "Flat", VoiceCurve.flat());
		x = template(x, y, 100, "Quiet middle", VoiceCurve.quietMiddle());
		template(x, y, 100, "Quiet older", VoiceCurve.quietOlder());
		this.addRenderableWidget(new LoopButton(this.width - SIDE - 80, y, 80, 20, Component.literal("Done"), PixelIcon.CHECK, Theme.BLUE,
			button -> onClose()).primary());
	}

	private int template(int x, int y, int width, String name, VoiceCurve template) {
		this.addRenderableWidget(new LoopButton(x, y, width, 20, Component.literal(name), PixelIcon.GRAPH, Theme.GREY, button -> {
			curve.points().clear();
			curve.points().addAll(template.points());
			apply();
		}));
		return x + width + 4;
	}

	private static int crowdFrom(double v) { return MIN_CROWD + (int) Math.round(v * (MAX_CROWD - MIN_CROWD)); }

	/** Hands the edited curve to the voice code and saves it. */
	private void apply() {
		curve.normalise();
		ClientSettings.voiceCurve = curve.copy();
		ClientSettings.save();
	}

	@Override
	public void onClose() { this.minecraft.gui.setScreen(parent); }

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int hit = pointAt(mx, my);
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (hit >= 0) { curve.removePoint(hit); apply(); return true; }
			return super.mouseClicked(event, doubleClick);
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
		if (hit >= 0) { dragPoint = hit; dragHandle = 0; return true; }
		for (int i = 0; i < curve.points().size(); i++) {
			for (int side = -1; side <= 1; side += 2) {
				if (near(mx, my, handleX(i, side), handleY(i, side))) { dragPoint = i; dragHandle = side; return true; }
			}
		}
		if (insideGraph(mx, my)) {
			// Double clicks are counted here: the game only counts one when the first click was used, and an empty spot
			// of the graph isn't used by the first click.
			long now = net.minecraft.util.Util.getMillis();
			boolean twice = now - lastGraphClick < DOUBLE_CLICK_MS && Math.abs(mx - lastGraphX) < 4 && Math.abs(my - lastGraphY) < 4;
			lastGraphClick = twice ? 0 : now;
			lastGraphX = mx;
			lastGraphY = my;
			if (twice) {
				int added = curve.addPoint(Math.max(0.01f, Math.min(0.99f, curveX(mx))));
				curve.points().get(added).y = Math.max(0, Math.min(1, curveY(my)));
				apply();
				dragPoint = added;
				dragHandle = 0;
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private static final long DOUBLE_CLICK_MS = 400;
	private long lastGraphClick;
	private double lastGraphX, lastGraphY;

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragPoint < 0) return super.mouseDragged(event, dx, dy);
		var points = curve.points();
		VoiceCurve.Point p = points.get(dragPoint);
		if (dragHandle == 0) {
			// The first and last points keep their place at the edges and only move up and down.
			if (dragPoint > 0 && dragPoint < points.size() - 1) {
				float left = points.get(dragPoint - 1).x + 0.01f, right = points.get(dragPoint + 1).x - 0.01f;
				p.x = Math.max(left, Math.min(right, curveX(event.x())));
			}
			p.y = Math.max(0, Math.min(1, curveY(event.y())));
		} else {
			p.handleX = (curveX(event.x()) - p.x) * dragHandle;
			p.handleY = (curveY(event.y()) - p.y) * dragHandle;
		}
		curve.normalise();
		ClientSettings.voiceCurve = curve.copy();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragPoint < 0) return super.mouseReleased(event);
		dragPoint = -1;
		apply();
		return true;
	}

	private int pointAt(double mx, double my) {
		var points = curve.points();
		for (int i = 0; i < points.size(); i++) if (near(mx, my, screenX(points.get(i).x), screenY(points.get(i).y))) return i;
		return -1;
	}

	private float handleX(int i, int side) { VoiceCurve.Point p = curve.points().get(i); return screenX(p.x + p.handleX * side); }

	private float handleY(int i, int side) { VoiceCurve.Point p = curve.points().get(i); return screenY(p.y + p.handleY * side); }

	private static boolean near(double mx, double my, float x, float y) { return Math.abs(mx - x) <= GRAB_DISTANCE && Math.abs(my - y) <= GRAB_DISTANCE; }

	private boolean insideGraph(double mx, double my) {
		return mx >= graphX() && mx <= graphX() + graphWidth() && my >= graphY() && my <= graphY() + graphHeight();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = graphX(), y = graphY(), w = graphWidth(), h = graphHeight();
		Theme.panel(g, x - 1, y - 1, w + 2, h + 2);
		// Grid lines every 25%, with their labels on the left.
		for (int i = 0; i <= 4; i++) {
			int lineY = Math.round(screenY(i / 4f));
			g.fill(x, lineY, x + w, lineY + 1, 0x28FFFFFF);
			String label = (i * 25) + "%";
			g.text(this.font, label, x - 4 - this.font.width(label), lineY - 4, Theme.TEXT_DIM);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		g.text(this.font, this.title, SIDE, 12, Theme.TITLE);
		g.text(this.font, "Oldest", x, y + h + 4, Theme.TEXT_DIM);
		String newest = "Newest";
		g.text(this.font, newest, x + w - this.font.width(newest), y + h + 4, Theme.TEXT_DIM);
		String help = "Drag the points and handles. Double-click to add a point, right-click one to remove it.";
		g.text(this.font, help, x + (w - this.font.width(help)) / 2, y + h + 16, Theme.TEXT_OFF);

		// The curve, one column at a time, joined up so steep parts stay solid.
		int previous = Math.round(screenY(curve.at(0)));
		for (int column = 0; column < w; column++) {
			int now = Math.round(screenY(curve.at(column / (float) (w - 1))));
			int top = Math.min(previous, now), bottom = Math.max(previous, now);
			g.fill(x + column, top - 1, x + column + 1, bottom + 1, Theme.BLUE);
			previous = now;
		}

		// Points with their handles.
		var points = curve.points();
		for (int i = 0; i < points.size(); i++) {
			float px = screenX(points.get(i).x), py = screenY(points.get(i).y);
			for (int side = -1; side <= 1; side += 2) {
				if ((i == 0 && side < 0) || (i == points.size() - 1 && side > 0)) continue;
				float hx = handleX(i, side), hy = handleY(i, side);
				dottedLine(g, px, py, hx, hy);
				int s = HANDLE_SIZE / 2;
				g.fill(Math.round(hx) - s, Math.round(hy) - s, Math.round(hx) + s, Math.round(hy) + s, Theme.TEXT_DIM);
			}
			int s = POINT_SIZE / 2;
			boolean hot = i == dragPoint || near(mouseX, mouseY, px, py);
			g.fill(Math.round(px) - s, Math.round(py) - s, Math.round(px) + s, Math.round(py) + s, hot ? Theme.YELLOW : Theme.TITLE);
		}
	}

	private static void dottedLine(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1) {
		float length = (float) Math.hypot(x1 - x0, y1 - y0);
		for (float d = 0; d < length; d += 3) {
			int x = Math.round(x0 + (x1 - x0) * d / length), y = Math.round(y0 + (y1 - y0) * d / length);
			g.fill(x, y, x + 1, y + 1, 0x90FFFFFF);
		}
	}

	@Override
	public boolean isPauseScreen() { return true; }
}
