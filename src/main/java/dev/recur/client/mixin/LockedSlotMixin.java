package dev.recur.client.mixin;

import dev.recur.client.ClientLoot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timed resets: hovering over an item a clone takes out later turns the slot's background red (you can't take it).
 * It's drawn behind the item, and the usual white hover highlight on top is left out so the red stays red.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class LockedSlotMixin {
	@Shadow protected Slot hoveredSlot;
	@Shadow @Final protected AbstractContainerMenu menu;

	@Unique
	private boolean recur$hoveringLocked() {
		Slot s = this.hoveredSlot;
		return s != null && s.hasItem() && ClientLoot.isLocked(this.menu.containerId, s.index);
	}

	@Inject(method = "extractSlotHighlightBack", at = @At("TAIL"))
	private void recur$redBackground(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (!recur$hoveringLocked()) return;
		Slot s = this.hoveredSlot;
		graphics.fill(s.x, s.y, s.x + 16, s.y + 16, dev.recur.client.Theme.WARNING);
	}

	@Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true)
	private void recur$noWhiteHighlight(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (recur$hoveringLocked()) ci.cancel();
	}
}
