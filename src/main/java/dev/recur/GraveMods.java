package dev.recur;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Gravestone mods. With timed resets on, a grave block from one of them would be undone by the next reset along with
 * the items in it, so Recur's own grave (which outlasts resets) takes the items first. Without timed resets they're
 * left alone.
 */
public final class GraveMods {
	private static final String[] IDS = { "yigd", "universal-graves", "universal_graves", "gravestones", "forgottengraves", "graves", "corpse", "gravestone" };
	private static Boolean present;

	private GraveMods() {}

	public static boolean present() {
		if (present == null) {
			present = false;
			for (String id : IDS) if (FabricLoader.getInstance().isModLoaded(id)) present = true;
		}
		return present;
	}

	/** The player is about to die (nothing has dropped yet). */
	public static void beforeDeath(ServerPlayer player) {
		if (!present() || !TimedReset.isActive() || player instanceof com.mt1006.mocap.utils.FakePlayer) return;
		if (Boolean.TRUE.equals(player.level().getGameRules().get(GameRules.KEEP_INVENTORY))) return;
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) inv.removeItemNoUpdate(i);
		}
		TimedReset.Graves.make(player);
	}
}
