package dev.recur.client;

import dev.recur.ServerSettings;

/** The settings of the world you're in, as the server last sent them (null when not in a Recur world). */
public final class ClientWorldSettings {
	private static ServerSettings.Values values;
	private static boolean canEdit;

	private ClientWorldSettings() {}

	public static void set(ServerSettings.Values v, boolean edit) { values = v; canEdit = edit; }

	public static void clear() { values = null; canEdit = false; }

	public static boolean known() { return values != null; }

	public static ServerSettings.Values values() { return values; }

	public static boolean canEdit() { return canEdit; }
}
