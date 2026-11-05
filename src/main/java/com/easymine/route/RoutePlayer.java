package com.easymine.route;

import java.util.ArrayList;
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
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import com.easymine.EasyMineClient;
import com.easymine.Settings;

/**
 * Plays the selected {@link Route} while the physical left mouse button is held and/or after the play/pause key was pressed,
 * depending on {@link Settings#playMode()}.
 *
 * <p><b>Gate vs. recording.</b> The hold gate reads the physical button straight from GLFW, never the attack key binding,
 * because playback itself drives that binding with the route's recorded left click. A route's own clicks are therefore never
 * confused with the "hold to play" click, and holding left click adds nothing to a recording (they never overlap: starting a
 * recording deselects the route).
 *
 * <p><b>Lockout.</b> While playing, every tick (before the game reads input) all key bindings are released and then exactly
 * the recorded keys/buttons are pressed, any queued clicks from the physical keyboard/mouse are discarded, and the camera is
 * driven by the route so the player's own mouse movement has no effect. Pausing (releasing left click, or pressing the
 * play/pause key again) releases the inputs and keeps the position; playing again resumes. Playback also pauses while a
 * screen is open.
 *
 * <p><b>Camera.</b> Keys are applied once per tick, but the camera is set every rendered frame by interpolating the route's
 * camera samples (recorded several times per tick), so playback is smooth. Whenever the camera has to return to the route's
 * angle (starting, resuming after a pause, or beginning a new loop) it eases there over {@link #BLEND_NANOS} instead of
 * snapping.
 *
 * <p><b>Background play.</b> A route started with the play/pause key keeps running while the window is unfocused, see
 * {@link #syncBackgroundPlay}. Holding left click cannot do this, because the button is released when focus is lost.
 *
 * <p>Everything stays on the client: only the key bindings, the player's own rotation and (temporarily, while a route
 * runs) the client's own "pause on lost focus" option are touched.
 */
public final class RoutePlayer {
	/** How long the camera takes to ease back onto the route's angle. */
	private static final long BLEND_NANOS = 300_000_000L;

	private static @Nullable KeyMapping playKey;

	/** True while this class has switched {@code pauseOnLostFocus} off; {@link #savedPauseOnLostFocus} is the value to restore. */
	private static boolean focusPauseOverridden;
	private static boolean savedPauseOnLostFocus;

	/** Whether the route's stand-on block was matched the last time it could be checked (kept while airborne). */
	private static boolean gateOpen;
	/** True once the "stand on X" message was shown for the current wait, so it is not repeated every tick. */
	private static boolean gateNotified;

	private static @Nullable Route route;
	private static int stepTick;
	private static int eventCursor;
	private static boolean repeat = true;

	/** Inputs the route currently holds down. */
	private static final Set<InputConstants.Key> desired = new HashSet<>();
	private static boolean active;
	/** True after the play/pause key started playback (cleared by pressing it again). */
	private static boolean keyPlaying;

	// Camera: the route's recorded turn (cumulative) as a curve over time, applied on top of a base angle.
	private static float[] curveTime = {0};
	private static float[] curveYaw = {0};
	private static float[] curvePitch = {0};
	private static float baseYaw;
	private static float basePitch;
	/** Timeline position of the most recent tick start; frames add the partial tick to it. */
	private static float tickStartTime;
	private static float lastYaw;
	private static float lastPitch;
	private static boolean blending;
	private static long blendStart;
	private static float blendYaw;
	private static float blendPitch;

	private RoutePlayer() {
	}

	public static void register() {
		playKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.easymine.toggle_playback", GLFW.GLFW_KEY_F9, EasyMineClient.KEY_CATEGORY));
		ClientTickEvents.START_CLIENT_TICK.register(RoutePlayer::onStartTick);
		LevelRenderEvents.START_MAIN.register(context -> onFrame(Minecraft.getInstance()));
	}

	/** True for this mod's play/pause binding, which playback leaves alone. */
	public static boolean isOwnMapping(KeyMapping mapping) {
		return mapping == playKey;
	}

	/** True if {@code key} is whatever the play/pause key is currently bound to. */
	public static boolean isPlaybackKey(InputConstants.Key key) {
		return playKey != null && !playKey.isUnbound() && playKey.saveString().equals(key.getName());
	}

	public static Component playKeyName() {
		return playKey == null ? Component.literal("?") : playKey.getTranslatedKeyMessage();
	}

	private static boolean leftHeld(Minecraft minecraft) {
		return GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
	}

	private static boolean wantsPlay(Minecraft minecraft) {
		Settings.PlayMode mode = Settings.playMode();
		return (mode.allowsHold() && leftHeld(minecraft)) || (mode.allowsKeybind() && keyPlaying);
	}

	private static void onStartTick(Minecraft minecraft) {
		tickPlayback(minecraft);
		syncBackgroundPlay(minecraft);
	}

	/**
	 * Keeps a key-started route running while the window is not focused. Minecraft opens the pause menu half a second after
	 * focus is lost when {@code pauseOnLostFocus} is on, and an open screen stops playback. So that option is switched off
	 * only while a route is actually running, and put back the moment it is not (route stopped or finished, a screen opened,
	 * or the world left), which also means it is never left off while any settings screen could save it.
	 *
	 * <p>Only a key-started route qualifies: the hold-left-click gate cannot be held while the window is unfocused.
	 */
	private static void syncBackgroundPlay(Minecraft minecraft) {
		boolean wanted = route != null && active && keyPlaying && Settings.backgroundPlay();
		if (wanted && !focusPauseOverridden) {
			savedPauseOnLostFocus = minecraft.options.pauseOnLostFocus;
			minecraft.options.pauseOnLostFocus = false;
			focusPauseOverridden = true;
		} else if (!wanted && focusPauseOverridden) {
			minecraft.options.pauseOnLostFocus = savedPauseOnLostFocus;
			focusPauseOverridden = false;
		}
	}

	private static void tickPlayback(Minecraft minecraft) {
		if (playKey != null) {
			while (playKey.consumeClick()) {
				togglePlayback();
			}
		}
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

		if (!wantsPlay(minecraft)) {
			gateNotified = false;
			if (active) {
				active = false;
				releaseKeys();
			}
			return;
		}
		if (!blockGateOpen(player)) {
			// Not on the route's block: hold still and wait. Playback picks up by itself once the player is back on it.
			if (active) {
				active = false;
				releaseKeys();
			}
			Route waiting = route;
			if (!gateNotified && waiting != null && waiting.standOn() != null) {
				gateNotified = true;
				overlay(Component.translatable("easymine.play.wrong_block", BlockGate.name(waiting.standOn()), waiting.name()));
			}
			return;
		}
		gateNotified = false;
		if (!active) {
			active = true;
			if (stepTick == 0) {
				resetBase(player);
			}
			// Resuming: the route's angle for this position is unchanged, so ease the camera from wherever the
			// player left it back onto that angle.
			startBlend(player, stepTick);
		}

		advance(minecraft, player);
	}

	/**
	 * Whether the route's stand-on block requirement (if it has one) is met right now. In {@code START} mode it is only
	 * checked at the beginning of a loop; in {@code ALWAYS} mode every tick. While airborne no block supports the player, so
	 * {@code ALWAYS} keeps its last answer (a jump must not pause the route) and {@code START} simply waits to land.
	 */
	private static boolean blockGateOpen(LocalPlayer player) {
		Route playing = route;
		Identifier required = playing == null ? null : playing.standOn();
		if (required == null) {
			return true;
		}
		boolean always = playing.standOnCheck() == Route.BlockCheck.ALWAYS;
		if (!always && stepTick != 0) {
			return true;
		}
		Identifier standing = BlockGate.standingOn(player);
		if (standing == null) {
			return always && gateOpen;
		}
		gateOpen = standing.equals(required);
		return gateOpen;
	}

	/** A selected route's settings were changed (for example its stand-on block): use the new version from now on. */
	public static void onRouteChanged(Route updated) {
		if (route != null && route.id().equals(updated.id())) {
			route = updated;
			gateOpen = false;
			gateNotified = false;
		}
	}

	/** Play/pause key pressed. */
	private static void togglePlayback() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!Settings.playMode().allowsKeybind() || minecraft.player == null || Recorder.isRecording()) {
			return;
		}
		if (route == null) {
			overlay(Component.translatable("easymine.play.none_selected"));
			return;
		}
		keyPlaying = !keyPlaying;
		overlay(Component.translatable(keyPlaying ? "easymine.play.resumed" : "easymine.play.paused", route.name()));
	}

	/** Called every rendered frame: keeps the camera moving smoothly between ticks. */
	private static void onFrame(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (route == null || !active || player == null || minecraft.gui.screen() != null || minecraft.isPaused()
				|| Recorder.isRecording() || !wantsPlay(minecraft)) {
			return;
		}
		float partial = Mth.clamp(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false), 0.0f, 1.0f);
		applyCamera(player, tickStartTime + partial);
	}

	private static void resetBase(LocalPlayer player) {
		Route playing = route;
		if (playing != null && playing.hasStartLook()) {
			// Start of every loop: turn back to exactly where the recording began.
			baseYaw = playing.startYaw();
			basePitch = Mth.clamp(playing.startPitch(), -90.0f, 90.0f);
		} else {
			baseYaw = player.getYRot();
			basePitch = player.getXRot();
		}
	}

	/** Makes the camera ease from its current angle onto the route's angle at {@code time}. */
	private static void startBlend(LocalPlayer player, float time) {
		blendYaw = Mth.wrapDegrees(player.getYRot() - targetYaw(time));
		blendPitch = player.getXRot() - targetPitch(time);
		blendStart = System.nanoTime();
		blending = true;
	}

	private static float targetYaw(float time) {
		return baseYaw + interpolate(curveYaw, time);
	}

	private static float targetPitch(float time) {
		return Mth.clamp(basePitch + interpolate(curvePitch, time), -90.0f, 90.0f);
	}

	private static float interpolate(float[] values, float time) {
		int last = curveTime.length - 1;
		if (time <= curveTime[0]) {
			return values[0];
		}
		if (time >= curveTime[last]) {
			return values[last];
		}
		int low = 0;
		int high = last;
		while (high - low > 1) {
			int mid = (low + high) >>> 1;
			if (curveTime[mid] <= time) {
				low = mid;
			} else {
				high = mid;
			}
		}
		float span = curveTime[high] - curveTime[low];
		float f = span <= 0 ? 1.0f : (time - curveTime[low]) / span;
		return values[low] + (values[high] - values[low]) * f;
	}

	private static void applyCamera(LocalPlayer player, float time) {
		float yaw = targetYaw(time);
		float pitch = targetPitch(time);
		if (blending) {
			float f = (System.nanoTime() - blendStart) / (float) BLEND_NANOS;
			if (f >= 1.0f) {
				blending = false;
			} else {
				float ease = f * f * (3.0f - 2.0f * f);
				float remaining = 1.0f - ease;
				yaw += blendYaw * remaining;
				pitch = Mth.clamp(pitch + blendPitch * remaining, -90.0f, 90.0f);
			}
		}
		player.setYRot(yaw);
		player.setXRot(pitch);
		// Old angle equal to the new one, so the renderer shows exactly this angle rather than blending from the last one.
		player.yRotO = yaw;
		player.xRotO = pitch;
		lastYaw = yaw;
		lastPitch = pitch;
	}

	private static void buildCurve(Route source) {
		List<Route.Event> looks = new ArrayList<>();
		for (Route.Event event : source.events()) {
			if (event.type() == Route.Type.LOOK) {
				looks.add(event);
			}
		}
		curveTime = new float[looks.size() + 1];
		curveYaw = new float[looks.size() + 1];
		curvePitch = new float[looks.size() + 1];
		float yaw = 0;
		float pitch = 0;
		for (int i = 0; i < looks.size(); i++) {
			Route.Event look = looks.get(i);
			yaw += look.yaw();
			pitch += look.pitch();
			curveTime[i + 1] = Math.max(look.time(), curveTime[i]);
			curveYaw[i + 1] = yaw;
			curvePitch[i + 1] = pitch;
		}
	}

	private static void advance(Minecraft minecraft, LocalPlayer player) {
		Route playing = route;
		if (playing == null) {
			return;
		}

		// Discard whatever the physical keyboard/mouse queued.
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!Recorder.isOwnMapping(mapping)) {
				while (mapping.consumeClick()) {
					// drained
				}
			}
		}

		List<Route.Event> events = playing.events();
		while (eventCursor < events.size() && events.get(eventCursor).tick() <= stepTick) {
			Route.Event event = events.get(eventCursor++);
			switch (event.type()) {
				case LOOK -> {
					// Camera samples are played from the interpolated curve, not applied one by one.
				}
				case KEY -> press(InputConstants.Type.KEYSYM.getOrCreate(event.code()), event.down());
				case MOUSE -> press(InputConstants.Type.MOUSE.getOrCreate(event.code()), event.down());
			}
		}

		tickStartTime = stepTick;
		applyCamera(player, tickStartTime);

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
				keyPlaying = false;
				route = null;
				overlay(Component.translatable("easymine.play.finished", playing.name()));
				return;
			}
			// New loop: ease from the angle we ended on back to where the route starts.
			resetBase(player);
			if (!playing.hasStartLook()) {
				baseYaw = lastYaw;
				basePitch = lastPitch;
			}
			blendYaw = Mth.wrapDegrees(lastYaw - targetYaw(0));
			blendPitch = lastPitch - targetPitch(0);
			blendStart = System.nanoTime();
			blending = true;
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

	/** Selects a route: it plays while left click is held and/or after the play/pause key is pressed. */
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
		buildCurve(toSelect);
		blending = false;
		keyPlaying = false;
		gateOpen = false;
		gateNotified = false;
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
		keyPlaying = false;
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

	/** The play mode setting changed: a pending key-started playback must not leak into the new mode. */
	public static void onPlayModeChanged() {
		keyPlaying = false;
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
