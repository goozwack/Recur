package dev.recur;

/**
 * While a clone's recording or recorded world changes are being played back. Items the world drops as a side effect of
 * that (a torch popping off when the clone breaks the block under it, loot of a mob the recording kills...) were already
 * recorded as items in the original run, so they mustn't drop a second time.
 */
public final class ReplayScope {
	/** > 0 while replaying. */
	public static int depth;
	/** > 0 while a block drops its items (Block.popResource). */
	public static int blockDrop;

	private ReplayScope() {}

	public static void reset() { depth = 0; blockDrop = 0; }
}
