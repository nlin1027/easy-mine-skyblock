package com.easymine.route;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Plays the selected {@link Route} while the physical left mouse button is held.
 *
 * <p><b>Gate vs. recording.</b> The gate reads the physical button straight from GLFW, never the attack key binding, because
 * playback itself drives that binding with the route's recorded left click. A route's own clicks are therefore never
 * confused with the "hold to play" click, and holding left click adds nothing to a recording (they never overlap: starting a
 * recording deselects the route).
 *
 * <p><b>Lockout.</b> While playing, every tick (before the game reads input) all key bindings are released and then exactly
 * the recorded keys/buttons are pressed, any queued clicks from the physical keyboard/mouse are discarded, and the camera is
 * put back to where playback left it before the recorded turn is applied. Keys the player presses and mouse movement during
 * playback therefore have no effect. Releasing left click pauses in place (inputs released, position kept); holding it again
 * resumes. Playback also pauses while a screen is open.
 *
 * <p>Everything stays on the client: only the key bindings and the player's own rotation are touched.
 */
public final class RoutePlayer {
	private static @Nullable Route route;
	private static int stepTick;
	private static int eventCursor;
	private static boolean repeat = true;

	/** Inputs the route currently holds down. */
	private static final Set<InputConstants.Key> desired = new HashSet<>();
	private static boolean active;
	private static float expectedYaw;
	private static float expectedPitch;

	private RoutePlayer() {
	}

	public static void register() {
		ClientTickEvents.START_CLIENT_TICK.register(RoutePlayer::onStartTick);
	}

	private static void onStartTick(Minecraft minecraft) {
		if (route == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop();
			return;
		}
		if (Recorder.isRecording()) {
			stop();
			return;
		}
		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			// The game released the keys itself; resume from a clean slate.
			active = false;
			return;
		}

		boolean held = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		if (!held) {
			if (active) {
				active = false;
				releaseKeys();
			}
			return;
		}
		if (!active) {
			active = true;
			if (stepTick == 0) {
				expectedYaw = player.getYRot();
				expectedPitch = player.getXRot();
			}
			// Otherwise resuming mid-route: expectedYaw/Pitch still hold the angle saved when left click was
			// released, and advance() snaps the camera back to it.
		}

		advance(minecraft, player);
	}

	private static void advance(Minecraft minecraft, LocalPlayer player) {
		Route playing = route;
		if (playing == null) {
			return;
		}

		// Discard whatever the physical keyboard/mouse queued, and undo the player's own mouse movement.
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!Recorder.isOwnMapping(mapping)) {
				while (mapping.consumeClick()) {
					// drained
				}
			}
		}
		if (stepTick == 0 && playing.hasStartLook()) {
			// Start of every loop: turn back to exactly where the recording began.
			expectedYaw = playing.startYaw();
			expectedPitch = Mth.clamp(playing.startPitch(), -90.0f, 90.0f);
		}
		player.setYRot(expectedYaw);
		player.setXRot(expectedPitch);

		float dYaw = 0;
		float dPitch = 0;
		List<Route.Event> events = playing.events();
		while (eventCursor < events.size() && events.get(eventCursor).tick() <= stepTick) {
			Route.Event event = events.get(eventCursor++);
			switch (event.type()) {
				case LOOK -> {
					dYaw += event.yaw();
					dPitch += event.pitch();
				}
				case KEY -> press(InputConstants.Type.KEYSYM.getOrCreate(event.code()), event.down());
				case MOUSE -> press(InputConstants.Type.MOUSE.getOrCreate(event.code()), event.down());
			}
		}

		if (dYaw != 0 || dPitch != 0) {
			player.setYRot(player.getYRot() + dYaw);
			player.setXRot(Mth.clamp(player.getXRot() + dPitch, -90.0f, 90.0f));
		}
		expectedYaw = player.getYRot();
		expectedPitch = player.getXRot();

		// Re-assert the held state every tick: release everything, then press exactly what the route holds.
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!Recorder.isOwnMapping(mapping)) {
				mapping.setDown(false);
			}
		}
		for (InputConstants.Key key : desired) {
			KeyMapping.set(key, true);
		}

		stepTick++;
		if (stepTick > playing.lengthTicks()) {
			desired.clear();
			releaseKeys();
			stepTick = 0;
			eventCursor = 0;
			if (!repeat) {
				active = false;
				route = null;
				overlay(Component.translatable("easymine.play.finished", playing.name()));
			}
		}
	}

	private static void press(InputConstants.Key key, boolean down) {
		if (down) {
			desired.add(key);
			KeyMapping.click(key);
		} else {
			desired.remove(key);
		}
	}

	/** Lets go of every binding, then hands them back to the physical keyboard/mouse state. */
	private static void releaseKeys() {
		Minecraft minecraft = Minecraft.getInstance();
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!Recorder.isOwnMapping(mapping)) {
				mapping.setDown(false);
			}
		}
		KeyMapping.setAll();
	}

	/** Selects a route: it plays while left click is held. */
	public static boolean select(Route toSelect) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		if (Recorder.isRecording()) {
			overlay(Component.translatable("easymine.play.busy_recording"));
			return false;
		}
		if (toSelect.events().isEmpty()) {
			overlay(Component.translatable("easymine.play.empty"));
			return false;
		}
		releaseIfActive();
		route = toSelect;
		stepTick = 0;
		eventCursor = 0;
		desired.clear();
		active = false;
		overlay(Component.translatable("easymine.play.selected", toSelect.name()));
		return true;
	}

	public static boolean toggle(Route toToggle) {
		if (isSelected(toToggle)) {
			stop();
			return false;
		}
		return select(toToggle);
	}

	/** Deselects the route and releases everything it held. */
	public static void stop() {
		if (route == null) {
			return;
		}
		Route old = route;
		releaseIfActive();
		route = null;
		desired.clear();
		active = false;
		overlay(Component.translatable("easymine.play.deselected", old.name()));
	}

	private static void releaseIfActive() {
		if (active || !desired.isEmpty()) {
			releaseKeys();
		}
	}

	private static void overlay(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(message);
		}
	}

	public static boolean isSelected(Route candidate) {
		return route != null && route.id().equals(candidate.id());
	}

	public static @Nullable Route selected() {
		return route;
	}

	public static boolean isPlaying() {
		return route != null && active;
	}

	public static boolean repeat() {
		return repeat;
	}

	public static void setRepeat(boolean value) {
		repeat = value;
	}

	public static int positionTicks() {
		return stepTick;
	}
}
