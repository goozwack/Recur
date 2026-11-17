package dev.recur.client;

import dev.recur.LoopNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Asks before cancelling the loop. The world can stay as it is now, or first go back to the selected checkpoint (or the
 * start) and stay like that.
 */
public class CancelLoopScreen extends ConfirmScreen {
	private final Screen back;

	public CancelLoopScreen(Screen back) {
		super(yes -> {}, Component.literal("Cancel the loop?"),
			Component.literal("Every clone is deleted and the world goes back to working like normal Minecraft. This can't be undone."));
		this.back = back;
	}

	@Override
	protected void addButtons(LinearLayout layout) {
		layout.addChild(Button.builder(Component.literal("Cancel here"), b -> send(0)).width(100)
			.tooltip(Tooltip.create(Component.literal("The world stays exactly as it is right now."))).build());
		// timed resets have no checkpoints
		if (ClientLoopState.timedTicks < 0) {
			String where = selectedName();
			layout.addChild(Button.builder(Component.literal(where == null ? "Reset to start, then cancel" : "Reset to checkpoint, then cancel"),
					b -> send(1)).width(190)
				.tooltip(Tooltip.create(Component.literal(where == null
					? "The world resets to how it was when the loop started, then stays like that."
					: "The world resets to \"" + where + "\" (with everything clones did up to it), then stays like that."))).build());
		}
		layout.addChild(Button.builder(CommonComponents.GUI_BACK, b -> this.minecraft.gui.setScreen(back)).width(80).build());
	}

	private static String selectedName() {
		for (LoopNetworking.CheckpointInfo c : ClientLoopState.checkpoints) if (c.id() == ClientLoopState.effectiveId) return c.name();
		return null;
	}

	private void send(int how) {
		ClientPlayNetworking.send(new LoopNetworking.ActionPayload(LoopNetworking.ACTION_CANCEL, how));
		this.minecraft.gui.setScreen(null);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(back);
	}
}
