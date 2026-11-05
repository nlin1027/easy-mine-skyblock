package com.easymine.route;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Routes as one JSON file each in {@code config/easymine/routes/}. Unreadable files are skipped and logged, never deleted.
 *
 * <pre>{@code
 * { "version": 1, "name": "Mithril lane", "createdAt": "...", "lengthTicks": 240,
 *   "startYaw": 90.0, "startPitch": 12.5,   (optional: camera angle at the start; playback restores it every loop)
 *   "standOn": "minecraft:gold_block", "standOnCheck": "start",   (optional: only play while standing on this block;
 *                                                                  "start" checks once per loop, "always" every tick)
 *   "events": [ { "tick": 0, "type": "key", "code": 87, "down": true },
 *               { "tick": 3, "frac": 0.57, "type": "look", "yaw": 1.5, "pitch": -0.25 } ] }
 *   (look events carry an optional sub-tick "frac"; several are recorded per tick for smoother playback)
 * }</pre>
 */
public final class RouteStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("easymine");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	private static final String EXTENSION = ".json";
	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
	public static final int MAX_NAME_LENGTH = 32;

	private RouteStore() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("easymine").resolve("routes");
	}

	/** All readable routes, newest first. */
	public static List<Route> list() {
		Path dir = directory();
		List<Route> routes = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return routes;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + EXTENSION)) {
			for (Path file : files) {
				try {
					routes.add(read(file));
				} catch (IOException | RuntimeException e) {
					LOGGER.warn("Skipping unreadable route {}: {}", file.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.error("Could not list routes in {}", dir, e);
		}
		routes.sort(Comparator.comparing(Route::createdAt).reversed());
		return routes;
	}

	public static Route saveNew(String name, Instant createdAt, int lengthTicks, float startYaw, float startPitch,
			List<Route.Event> events) throws IOException {
		Path dir = directory();
		Files.createDirectories(dir);
		String baseId = ID_FORMAT.format(createdAt);
		String id = baseId;
		for (int i = 2; Files.exists(dir.resolve(id + EXTENSION)); i++) {
			id = baseId + "_" + i;
		}
		Route route = new Route(id, name.strip(), createdAt, lengthTicks, startYaw, startPitch, events);
		write(route);
		return route;
	}

	public enum NameCheck {
		OK, EMPTY, TOO_LONG, TAKEN
	}

	public static NameCheck checkName(String name) {
		String trimmed = name.strip();
		if (trimmed.isEmpty()) {
			return NameCheck.EMPTY;
		}
		if (trimmed.length() > MAX_NAME_LENGTH) {
			return NameCheck.TOO_LONG;
		}
		return find(trimmed) != null ? NameCheck.TAKEN : NameCheck.OK;
	}

	public static @Nullable Route find(String name) {
		String wanted = name.strip().toLowerCase(Locale.ROOT);
		for (Route route : list()) {
			if (route.name().toLowerCase(Locale.ROOT).equals(wanted)) {
				return route;
			}
		}
		return null;
	}

	/** Rewrites an existing route's file, used when one of its settings (such as the stand-on block) changes. */
	public static void update(Route route) throws IOException {
		write(route);
	}

	public static void delete(Route route) throws IOException {
		Files.deleteIfExists(directory().resolve(route.id() + EXTENSION));
	}

	private static void write(Route route) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("version", FORMAT_VERSION);
		json.addProperty("name", route.name());
		json.addProperty("createdAt", route.createdAt().toString());
		json.addProperty("lengthTicks", route.lengthTicks());
		if (route.hasStartLook()) {
			json.addProperty("startYaw", route.startYaw());
			json.addProperty("startPitch", route.startPitch());
		}
		if (route.standOn() != null) {
			json.addProperty("standOn", route.standOn().toString());
			json.addProperty("standOnCheck", route.standOnCheck().name().toLowerCase(Locale.ROOT));
		}

		JsonArray events = new JsonArray();
		for (Route.Event event : route.events()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("tick", event.tick());
			entry.addProperty("type", event.type().name().toLowerCase(Locale.ROOT));
			if (event.type() == Route.Type.LOOK) {
				if (event.frac() != 0) {
					entry.addProperty("frac", event.frac());
				}
				entry.addProperty("yaw", event.yaw());
				entry.addProperty("pitch", event.pitch());
			} else {
				entry.addProperty("code", event.code());
				entry.addProperty("down", event.down());
			}
			events.add(entry);
		}
		json.add("events", events);

		// Write to a temporary file first so a crash mid-write never leaves a half-written route behind.
		Path target = directory().resolve(route.id() + EXTENSION);
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(json, writer);
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static Route read(Path file) throws IOException {
		JsonObject json;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			json = JsonParser.parseReader(reader).getAsJsonObject();
		}
		int version = json.get("version").getAsInt();
		if (version != FORMAT_VERSION) {
			throw new JsonParseException("unsupported format version " + version);
		}
		String fileName = file.getFileName().toString();
		String id = fileName.substring(0, fileName.length() - EXTENSION.length());
		int lengthTicks = json.get("lengthTicks").getAsInt();
		Instant createdAt = Instant.parse(json.get("createdAt").getAsString());

		List<Route.Event> events = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("events")) {
			JsonObject entry = element.getAsJsonObject();
			int tick = entry.get("tick").getAsInt();
			if (tick < 0 || tick > lengthTicks) {
				throw new JsonParseException("event tick " + tick + " outside 0.." + lengthTicks);
			}
			Route.Type type = Route.Type.valueOf(entry.get("type").getAsString().toUpperCase(Locale.ROOT));
			events.add(switch (type) {
				case KEY -> Route.Event.key(tick, entry.get("code").getAsInt(), entry.get("down").getAsBoolean());
				case MOUSE -> Route.Event.mouse(tick, entry.get("code").getAsInt(), entry.get("down").getAsBoolean());
				case LOOK -> Route.Event.look(tick, entry.has("frac") ? entry.get("frac").getAsFloat() : 0f,
						entry.get("yaw").getAsFloat(), entry.get("pitch").getAsFloat());
			});
		}
		events.sort(Comparator.comparingDouble(Route.Event::time));
		float startYaw = json.has("startYaw") ? json.get("startYaw").getAsFloat() : Float.NaN;
		float startPitch = json.has("startPitch") ? json.get("startPitch").getAsFloat() : Float.NaN;
		Identifier standOn = json.has("standOn") ? Identifier.tryParse(json.get("standOn").getAsString()) : null;
		Route.BlockCheck check = json.has("standOnCheck")
				? Route.BlockCheck.valueOf(json.get("standOnCheck").getAsString().toUpperCase(Locale.ROOT))
				: Route.BlockCheck.START;
		return new Route(id, json.get("name").getAsString(), createdAt, lengthTicks, startYaw, startPitch, events,
				standOn, check);
	}
}
