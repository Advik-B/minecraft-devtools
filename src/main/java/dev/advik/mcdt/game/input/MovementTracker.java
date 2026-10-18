package dev.advik.mcdt.game.input;

import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages timed movement key holds across client ticks.
 */
public final class MovementTracker {
	private static final MovementTracker INSTANCE = new MovementTracker();

	public static MovementTracker getInstance() {
		return INSTANCE;
	}

	private final AtomicInteger remainingTicks = new AtomicInteger(0);
	private volatile boolean active = false;

	private MovementTracker() {}

	public synchronized void start(boolean forward, boolean backward, boolean left, boolean right,
								   boolean jump, boolean sneak, boolean sprint, int ticks) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return;
		}
		var opt = client.options;

		opt.keyUp.setDown(forward);
		opt.keyDown.setDown(backward);
		opt.keyLeft.setDown(left);
		opt.keyRight.setDown(right);
		opt.keyJump.setDown(jump);
		opt.keyShift.setDown(sneak);
		opt.keySprint.setDown(sprint);

		active = forward || backward || left || right || jump || sneak || sprint;
		remainingTicks.set(ticks);
	}

	public synchronized void stop() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return;
		}
		var opt = client.options;

		opt.keyUp.setDown(false);
		opt.keyDown.setDown(false);
		opt.keyLeft.setDown(false);
		opt.keyRight.setDown(false);
		opt.keyJump.setDown(false);
		opt.keyShift.setDown(false);
		opt.keySprint.setDown(false);

		active = false;
		remainingTicks.set(0);
	}

	public synchronized void tick() {
		if (!active) {
			return;
		}

		int ticks = remainingTicks.get();
		if (ticks > 0) {
			int left = remainingTicks.decrementAndGet();
			if (left <= 0) {
				stop();
			}
		}
	}

	public int getRemainingTicks() {
		return remainingTicks.get();
	}

	public boolean isActive() {
		return active;
	}
}
