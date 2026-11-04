package com.easymine.route;

import org.jspecify.annotations.Nullable;

/** STUB: the master agent is writing the real implementation; the public API below is the contract. */
public final class RoutePlayer {
	private RoutePlayer() {
	}

	public static void register() {
	}

	/** Selects a route (armed: it plays while left click is held). Returns false if it could not be selected. */
	public static boolean select(Route route) {
		return false;
	}

	/** Selecting the already-selected route deselects it. Returns whether {@code route} is selected afterwards. */
	public static boolean toggle(Route route) {
		return false;
	}

	/** Deselects and releases everything. Safe to call anytime. */
	public static void stop() {
	}

	public static boolean isSelected(Route route) {
		return false;
	}

	public static @Nullable Route selected() {
		return null;
	}

	/** A route is selected and left click is currently held, so it is actually playing. */
	public static boolean isPlaying() {
		return false;
	}

	/** Whether the route restarts when it ends (default true). */
	public static boolean repeat() {
		return true;
	}

	public static void setRepeat(boolean repeat) {
	}

	/** 0-based tick position inside the selected route. */
	public static int positionTicks() {
		return 0;
	}
}
