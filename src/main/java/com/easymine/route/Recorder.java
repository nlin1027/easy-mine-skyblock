package com.easymine.route;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.easymine.EasyMineClient;

/**
 * Captures every keyboard key, every mouse button and the camera once per client tick.
 *
 * <p>Input is read straight from GLFW (the physical state of the keys), not from the key bindings, so every key is
 * captured whether or not Minecraft binds it. Camera movement is recorded as the change in yaw/pitch since the previous
 * tick. Nothing is sent anywhere.
 *
 * <p>Recording pauses while any screen is open (chat, inventory, this mod's own screen) so typing and menu clicks never end
 * up in a route. Escape, the chat and command keys and this mod's own record key are never recorded.
 */
public final class Recorder {
	private static final Logger LOGGER = LoggerFactory.getLogger("easymine");

	/** Hard cap so a forgotten recording does not grow forever: 30 minutes. */
	public static final int MAX_TICKS = 30 * 60 * Route.TICKS_PER_SECOND;

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(EasyMineClient.MOD_ID, "main"));

	/** Every GLFW keyboard key code worth polling (polling an unknown code makes GLFW log an error). */
	private static final int[] KEY_CODES = buildKeyCodes();
	private static final int MOUSE_BUTTONS = 8;

	private static @Nullable KeyMapping toggleKey;

	private static boolean recording;
	private static Instant startedAt = Instant.EPOCH;
	private static int tick;
	private static final List<Route.Event> events = new ArrayList<>();
	private static final boolean[] keyHeld = new boolean[GLFW.GLFW_KEY_LAST + 1];
	private static final boolean[] mouseHeld = new boolean[MOUSE_BUTTONS];
	private static float lastYaw;
	private static float lastPitch;
	private static boolean haveLook;

	private static String activeName = "";
	private static String draftName = "";
	private static @Nullable Component lastResult;

	private Recorder() {
	}

	private static int[] buildKeyCodes() {
		List<Integer> codes = new ArrayList<>();
		for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
			boolean valid = code == 32 || code == 39 || (code >= 44 && code <= 57) || code == 59 || code == 61
					|| (code >= 65 && code <= 93) || code == 96 || code == 161 || code == 162
					|| (code >= 256 && code <= 269) || (code >= 280 && code <= 284)
					|| (code >= 290 && code <= 314) || (code >= 320 && code <= 348);
			if (valid) {
				codes.add(code);
			}
		}
		return codes.stream().mapToInt(Integer::intValue).toArray();
	}

	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.easymine.toggle_recording", GLFW.GLFW_KEY_F8, CATEGORY));
		ClientTickEvents.START_CLIENT_TICK.register(Recorder::onStartTick);
	}

	/** True for this mod's own bindings, which playback leaves alone. */
	public static boolean isOwnMapping(KeyMapping mapping) {
		return mapping == toggleKey;
	}

	private static void onStartTick(Minecraft minecraft) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				if (recording) {
					stop();
				} else {
					Component error = start(draftName);
					if (error != null && minecraft.player != null) {
						minecraft.player.sendOverlayMessage(error);
					}
				}
			}
		}

		if (!recording) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop();
			return;
		}
		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			// Forget the camera so a screen's mouse movement is not counted as a turn.
			haveLook = false;
			return;
		}

		sampleCamera(player);
		sampleInput(minecraft);
		tick++;

		if (tick >= MAX_TICKS) {
			stop();
		}
	}

	private static void sampleCamera(LocalPlayer player) {
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		if (haveLook) {
			float dYaw = yaw - lastYaw;
			float dPitch = pitch - lastPitch;
			if (Math.abs(dYaw) > 1e-4f || Math.abs(dPitch) > 1e-4f) {
				events.add(Route.Event.look(tick, dYaw, dPitch));
			}
		}
		lastYaw = yaw;
		lastPitch = pitch;
		haveLook = true;
	}

	private static void sampleInput(Minecraft minecraft) {
		long window = minecraft.getWindow().handle();
		for (int code : KEY_CODES) {
			boolean down = GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS && !isExcluded(minecraft, code);
			if (down != keyHeld[code]) {
				keyHeld[code] = down;
				events.add(Route.Event.key(tick, code, down));
			}
		}
		for (int button = 0; button < MOUSE_BUTTONS; button++) {
			boolean down = GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
			if (down != mouseHeld[button]) {
				mouseHeld[button] = down;
				events.add(Route.Event.mouse(tick, button, down));
			}
		}
	}

	/** Keys that must never be replayed: they would open screens or control the recorder itself. */
	private static boolean isExcluded(Minecraft minecraft, int code) {
		if (code == GLFW.GLFW_KEY_ESCAPE) {
			return true;
		}
		InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
		return (toggleKey != null && toggleKey.matches(key))
				|| minecraft.options.keyChat.matches(key)
				|| minecraft.options.keyCommand.matches(key);
	}

	/** @return {@code null} on success, otherwise the reason nothing started */
	public static @Nullable Component start(String name) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return Component.translatable("easymine.record.error.no_world");
		}
		if (recording) {
			return Component.translatable("easymine.record.error.already_recording", activeName);
		}
		Component nameError = nameError(name);
		if (nameError != null) {
			return nameError;
		}

		RoutePlayer.stop();
		recording = true;
		activeName = name.strip();
		draftName = "";
		startedAt = Instant.now();
		tick = 0;
		events.clear();
		java.util.Arrays.fill(keyHeld, false);
		java.util.Arrays.fill(mouseHeld, false);
		haveLook = false;
		lastResult = null;

		minecraft.player.sendOverlayMessage(Component.translatable("easymine.record.overlay.started", activeName, toggleKeyName()));
		return null;
	}

	public static @Nullable Component nameError(String name) {
		return switch (RouteStore.checkName(name)) {
			case OK -> null;
			case EMPTY -> Component.translatable("easymine.record.error.name_empty");
			case TOO_LONG -> Component.translatable("easymine.record.error.name_too_long", RouteStore.MAX_NAME_LENGTH);
			case TAKEN -> Component.translatable("easymine.record.error.name_taken", name.strip());
		};
	}

	public static String draftName() {
		return draftName;
	}

	public static void setDraftName(String name) {
		draftName = name;
	}

	public static String activeName() {
		return recording ? activeName : "";
	}

	public static Component toggleKeyName() {
		return toggleKey == null ? Component.literal("?") : toggleKey.getTranslatedKeyMessage();
	}

	/** Stops and saves the recording (unless nothing was pressed or turned). */
	public static @Nullable Route stop() {
		if (!recording) {
			return null;
		}
		recording = false;
		Route saved = null;

		// Close everything still held so playback always ends with nothing pressed.
		for (int code : KEY_CODES) {
			if (keyHeld[code]) {
				keyHeld[code] = false;
				events.add(Route.Event.key(tick, code, false));
			}
		}
		for (int button = 0; button < MOUSE_BUTTONS; button++) {
			if (mouseHeld[button]) {
				mouseHeld[button] = false;
				events.add(Route.Event.mouse(tick, button, false));
			}
		}

		if (events.isEmpty()) {
			lastResult = Component.translatable("easymine.record.result.empty");
			draftName = activeName;
		} else {
			try {
				saved = RouteStore.saveNew(activeName, startedAt, tick, events);
				lastResult = Component.translatable("easymine.record.result.saved",
						saved.name(), Route.formatDuration(saved.lengthTicks()));
			} catch (IOException e) {
				LOGGER.error("Could not save route", e);
				lastResult = Component.translatable("easymine.record.result.failed");
			}
		}
		events.clear();

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && lastResult != null) {
			minecraft.player.sendOverlayMessage(lastResult);
		}
		return saved;
	}

	public static boolean isRecording() {
		return recording;
	}

	public static int elapsedTicks() {
		return tick;
	}

	public static @Nullable Component lastResult() {
		return lastResult;
	}
}
