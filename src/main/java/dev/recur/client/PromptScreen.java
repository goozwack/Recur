package dev.recur.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A question that pops up on its own while someone is playing (accept a checkpoint, the tutorial).
 * They're probably holding keys or clicking when it appears, so for the first moment it ignores input, and no button
 * starts focused: otherwise a held Space or Enter (jumping, sending a chat message) would press a button instantly.
 */
public abstract class PromptScreen extends Screen {
	private static final int IGNORE_TICKS = 10;
	private int age;

	protected PromptScreen(Component title) {
		super(title);
	}

	@Override
	protected void setInitialFocus() {} // Tab still focuses buttons

	@Override
	public void tick() {
		super.tick();
		age++;
	}

	protected boolean ready() {
		return age >= IGNORE_TICKS;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return ready() ? super.keyPressed(event) : true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return ready() ? super.mouseClicked(event, doubleClick) : true;
	}
}
