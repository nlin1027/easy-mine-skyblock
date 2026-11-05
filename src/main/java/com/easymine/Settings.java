package com.easymine;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/** Mod settings, saved as {@code config/easymine/settings.json}. */
public final class Settings {
	private static final Logger LOGGER = LoggerFactory.getLogger("easymine");

	/** What starts and pauses the selected route. */
	public enum PlayMode {
		/** Plays only while the left mouse button is held. */
		HOLD,
		/** The play/pause key toggles playback. */
		KEYBIND,
		/** Either one works. */
		BOTH;

		public PlayMode next() {
			return values()[(this.ordinal() + 1) % values().length];
		}

		public boolean allowsHold() {
			return this != KEYBIND;
		}

		public boolean allowsKeybind() {
			return this != HOLD;
		}
	}

	private static PlayMode playMode = PlayMode.HOLD;
	private static boolean backgroundPlay = true;

	private Settings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("easymine").resolve("settings.json");
	}

	public static PlayMode playMode() {
		return playMode;
	}

	public static void setPlayMode(PlayMode mode) {
		playMode = mode;
		save();
	}

	/**
	 * Whether a route started with the play/pause key keeps playing while the game window is not focused (alt-tabbed or
	 * minimized), instead of Minecraft's "pause on lost focus" opening the pause menu and stopping it.
	 */
	public static boolean backgroundPlay() {
		return backgroundPlay;
	}

	public static void setBackgroundPlay(boolean value) {
		backgroundPlay = value;
		save();
	}

	public static void load() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			if (json.has("playMode")) {
				playMode = PlayMode.valueOf(json.get("playMode").getAsString().toUpperCase(Locale.ROOT));
			}
			if (json.has("backgroundPlay")) {
				backgroundPlay = json.get("backgroundPlay").getAsBoolean();
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read settings, using defaults: {}", e.toString());
		}
	}

	private static void save() {
		JsonObject json = new JsonObject();
		json.addProperty("playMode", playMode.name().toLowerCase(Locale.ROOT));
		json.addProperty("backgroundPlay", backgroundPlay);
		try {
			Files.createDirectories(file().getParent());
			try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				writer.write(json.toString());
			}
		} catch (IOException e) {
			LOGGER.warn("Could not save settings", e);
		}
	}
}
