package dev.recur.client;

import java.util.ArrayList;
import java.util.List;

/**
 * How loud each clone's voice is when many clones talk over each other, by how recent the clone is within that group
 * (x: 0 = the oldest one talking, 1 = the newest; y: volume from 0 to 1).
 * The curve is a chain of cubic bezier segments through its points. Each point has one handle, mirrored on both sides,
 * so the curve stays smooth through it. The first and last points always sit at x = 0 and x = 1.
 */
public final class VoiceCurve {
	/** One point of the curve with its handle (handle x and y are offsets from the point). */
	public static final class Point {
		public float x, y, handleX, handleY;

		public Point(float x, float y, float handleX, float handleY) {
			this.x = x; this.y = y; this.handleX = handleX; this.handleY = handleY;
		}

		Point copy() { return new Point(x, y, handleX, handleY); }
	}

	private final List<Point> points = new ArrayList<>();

	public VoiceCurve(List<Point> points) {
		for (Point p : points) this.points.add(p.copy());
		normalise();
	}

	public List<Point> points() { return points; }

	public VoiceCurve copy() { return new VoiceCurve(points); }

	/** Every clone at full volume. */
	public static VoiceCurve flat() {
		return new VoiceCurve(List.of(new Point(0, 1, 0.2f, 0), new Point(1, 1, 0.2f, 0)));
	}

	/** The oldest and newest clones stay loud, the ones in between are quieter. */
	public static VoiceCurve quietMiddle() {
		return new VoiceCurve(List.of(new Point(0, 1, 0.12f, 0), new Point(0.5f, 0.3f, 0.15f, 0), new Point(1, 1, 0.12f, 0)));
	}

	/** Only the newest clones stay loud. */
	public static VoiceCurve quietOlder() {
		return new VoiceCurve(List.of(new Point(0, 0.25f, 0.15f, 0), new Point(0.6f, 0.3f, 0.12f, 0), new Point(1, 1, 0.12f, 0)));
	}

	/** The volume at this recency (0..1). */
	public float at(float x) {
		x = clamp(x, 0, 1);
		for (int i = 0; i < points.size() - 1; i++) {
			Point a = points.get(i), b = points.get(i + 1);
			if (x > b.x && i < points.size() - 2) continue;
			// The segment's x grows with t (handles are kept inside the segment), so t can be found by bisection.
			float low = 0, high = 1;
			for (int step = 0; step < 24; step++) {
				float mid = (low + high) / 2;
				if (bezier(a.x, a.x + a.handleX, b.x - b.handleX, b.x, mid) < x) low = mid; else high = mid;
			}
			float t = (low + high) / 2;
			return clamp(bezier(a.y, a.y + a.handleY, b.y - b.handleY, b.y, t), 0, 1);
		}
		return points.get(points.size() - 1).y;
	}

	private static float bezier(float p0, float p1, float p2, float p3, float t) {
		float u = 1 - t;
		return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
	}

	/** Adds a point on the curve at this x, with a flat handle. Returns its index. */
	public int addPoint(float x) {
		Point p = new Point(x, at(x), 0.08f, 0);
		int i = 1;
		while (i < points.size() - 1 && points.get(i).x < x) i++;
		points.add(i, p);
		normalise();
		return points.indexOf(p);
	}

	/** Removes a point (never the first or last). */
	public void removePoint(int index) {
		if (index > 0 && index < points.size() - 1) points.remove(index);
		normalise();
	}

	/** Keeps the curve valid: ends at x = 0 and 1, points in order, values in range, handles inside their segments. */
	public void normalise() {
		if (points.size() < 2) { points.clear(); points.addAll(flat().points); return; }
		points.sort((p, q) -> Float.compare(p.x, q.x));
		points.get(0).x = 0;
		points.get(points.size() - 1).x = 1;
		for (int i = 0; i < points.size(); i++) {
			Point p = points.get(i);
			p.x = clamp(p.x, 0, 1);
			p.y = clamp(p.y, 0, 1);
			// The handle reaches at most to the neighbouring points, so each segment's x keeps growing.
			float room = Math.min(i > 0 ? p.x - points.get(i - 1).x : 1, i < points.size() - 1 ? points.get(i + 1).x - p.x : 1);
			p.handleX = clamp(p.handleX, 0, Math.max(0, room));
			p.handleY = clamp(p.handleY, -1, 1);
		}
	}

	private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

	/** "x,y,handleX,handleY;..." */
	public String serialise() {
		StringBuilder s = new StringBuilder();
		for (Point p : points) {
			if (!s.isEmpty()) s.append(';');
			s.append(p.x).append(',').append(p.y).append(',').append(p.handleX).append(',').append(p.handleY);
		}
		return s.toString();
	}

	public static VoiceCurve parse(String text) {
		List<Point> list = new ArrayList<>();
		try {
			for (String part : text.split(";")) {
				String[] v = part.split(",");
				if (v.length == 4) list.add(new Point(Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]), Float.parseFloat(v[3])));
			}
		} catch (NumberFormatException e) {
			return flat();
		}
		return list.size() >= 2 ? new VoiceCurve(list) : flat();
	}
}
