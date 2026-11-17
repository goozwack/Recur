package dev.recur;

import com.mt1006.mocap.utils.FakePlayer;
import dev.recur.mixin.VaultServerDataAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vaults open once per person, and a clone is its player. Whoever opens one first on the timeline gets it: a player who
 * opens it before their clone would have makes the clone's try fail, and a clone that opened it first leaves it used for
 * its player. A clone's vault gives exactly what it gave in the recording (those items are in the recording), only when
 * the vault really opened for it in this run.
 */
public final class TrialVaults {
	/** Clones a vault opened for this run, as "clone id|vault". */
	private static final Set<String> OPENED = new HashSet<>();
	/** Vaults ejecting for a clone right now (when it started): what they roll doesn't come out. */
	private static final Map<String, Long> CLONE_EJECTING = new HashMap<>();

	private TrialVaults() {}

	public static String key(Level l, BlockPos p) { return l.dimension().identifier() + "|" + p.asLong(); }

	/** Who opens a vault: a clone counts as its player. Null for a live player (their own id is used). */
	public static UUID person(Player p) { return p instanceof FakePlayer ? LoopManager.ownerOf(p) : null; }

	/** A clone uses a vault in its replay. */
	public static void cloneUse(ServerLevel level, Player clone, BlockPos pos, BlockState state) {
		if (!(level.getBlockEntity(pos) instanceof VaultBlockEntity vault)) return;
		UUID who = person(clone);
		if (who == null) who = clone.getUUID();
		Set<UUID> rewarded = ((VaultServerDataAccessor) vault.getServerData()).recur$rewardedPlayers();
		boolean before = rewarded.contains(who);
		ItemStack key = clone.getMainHandItem().isEmpty() ? clone.getOffhandItem() : clone.getMainHandItem();
		VaultBlockEntity.Server.tryInsertKey(level, pos, state, vault.getConfig(), vault.getServerData(), vault.getSharedData(), clone, key.copy());
		if (!before && rewarded.contains(who)) {
			OPENED.add(clone.getUUID() + "|" + key(level, pos));
			CLONE_EJECTING.put(key(level, pos), level.getGameTime());
		}
	}

	/** Anyone using a vault: a live player's loot is theirs. */
	public static void onInsert(ServerLevel level, BlockPos pos, Player player) {
		if (!(player instanceof FakePlayer)) CLONE_EJECTING.remove(key(level, pos));
	}

	public static boolean blockEject(Level level, BlockPos pos) {
		Long since = CLONE_EJECTING.get(key(level, pos));
		return since != null && level.getGameTime() - since < 600;
	}

	/** Did the vault open for this clone in this run? Then what it gave in the recording drops. */
	public static boolean openedFor(Entity clone, String vault) { return clone != null && OPENED.contains(clone.getUUID() + "|" + vault); }

	public static void clear() { OPENED.clear(); CLONE_EJECTING.clear(); }
}
