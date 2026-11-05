package com.easymine.route;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;

/**
 * A saved run of input: every keyboard key, every mouse button and the camera, on a game-tick timeline (20 ticks/second).
 *
 * @param id             file name without {@code .json}
 * @param name           display name
 * @param createdAt      when recording started
 * @param lengthTicks    how long the route lasts
 * @param startYaw       exact camera yaw when recording began ({@link Float#NaN} for routes saved before this was stored)
 * @param startPitch     exact camera pitch when recording began ({@link Float#NaN} if unknown)
 * @param events         in tick order
 * @param standOn        the block the player must be standing on for the route to play, or null to play anywhere
 * @param standOnCheck   when {@code standOn} is checked (ignored if {@code standOn} is null)
 */
public record Route(String id, String name, Instant createdAt, int lengthTicks, float startYaw, float startPitch,
		List<Event> events, @Nullable Identifier standOn, BlockCheck standOnCheck) {
	public static final int TICKS_PER_SECOND = 20;

	/** When the stand-on block is checked. */
	public enum BlockCheck {
		/** Only when the route starts or loops back to its beginning; once running it plays on, wherever it walks. */
		START,
		/** Every tick: the route pauses whenever the player is not on the block. */
		ALWAYS;

		public BlockCheck next() {
			return this == START ? ALWAYS : START;
		}
	}

	public Route {
		events = List.copyOf(events);
	}

	/** A route with no stand-on block. */
	public Route(String id, String name, Instant createdAt, int lengthTicks, float startYaw, float startPitch,
			List<Event> events) {
		this(id, name, createdAt, lengthTicks, startYaw, startPitch, events, null, BlockCheck.START);
	}

	/** The same route with a different stand-on setting ({@code block} null means play anywhere). */
	public Route withStandOn(@Nullable Identifier block, BlockCheck check) {
		return new Route(this.id, this.name, this.createdAt, this.lengthTicks, this.startYaw, this.startPitch, this.events,
				block, check);
	}

	/** False for routes recorded before the starting camera angle was saved; playback then keeps the current angle. */
	public boolean hasStartLook() {
		return !Float.isNaN(this.startYaw) && !Float.isNaN(this.startPitch);
	}

	public enum Type {
		/** A keyboard key (GLFW key code) going down or up. */
		KEY,
		/** A mouse button going down or up. */
		MOUSE,
		/** The camera turning: yaw/pitch are the change since the previous camera sample (several per tick). */
		LOOK
	}

	/** One thing that happened on a tick. code/down apply to KEY and MOUSE, yaw/pitch to LOOK. */
	public record Event(int tick, float frac, Type type, int code, boolean down, float yaw, float pitch) {
		public static Event key(int tick, int code, boolean down) {
			return new Event(tick, 0, Type.KEY, code, down, 0, 0);
		}

		public static Event mouse(int tick, int button, boolean down) {
			return new Event(tick, 0, Type.MOUSE, button, down, 0, 0);
		}

		public static Event look(int tick, float yaw, float pitch) {
			return look(tick, 0, yaw, pitch);
		}

		/** A camera sample part-way through a tick: {@code frac} is in [0, 1). */
		public static Event look(int tick, float frac, float yaw, float pitch) {
			return new Event(tick, frac, Type.LOOK, 0, false, yaw, pitch);
		}

		/** Position on the timeline in ticks, including the sub-tick fraction used by camera samples. */
		public float time() {
			return this.tick + this.frac;
		}
	}

	public int inputCount() {
		int count = 0;
		for (Event event : this.events) {
			if (event.type() != Type.LOOK && event.down()) {
				count++;
			}
		}
		return count;
	}

	public static String formatDuration(int ticks) {
		int totalSeconds = ticks / TICKS_PER_SECOND;
		int hours = totalSeconds / 3600;
		int minutes = totalSeconds / 60 % 60;
		int seconds = totalSeconds % 60;
		return hours > 0
				? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
				: String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}
}
