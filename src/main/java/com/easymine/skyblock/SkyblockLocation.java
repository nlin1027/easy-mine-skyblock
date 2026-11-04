package com.easymine.skyblock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Detects whether the player is in a Hypixel SkyBlock mining area (Dwarven Mines or Crystal Hollows). Reads only what the
 * client already holds: the tab list, with the sidebar scoreboard as a fallback. Nothing is sent anywhere.
 */
public final class SkyblockLocation {
	private static @Nullable BooleanSupplier override;

	private SkyblockLocation() {
	}

	/** Test hook: forces the result of {@link #isInMiningArea()}. Pass null to restore real detection. */
	public static void setOverride(@Nullable BooleanSupplier value) {
		override = value;
	}

	public static boolean isInMiningArea() {
		BooleanSupplier forced = override;
		if (forced != null) {
			return forced.getAsBoolean();
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (minecraft.level == null || connection == null) {
			return false;
		}
		List<String> tab = new ArrayList<>();
		Collection<PlayerInfo> players = connection.getListedOnlinePlayers();
		for (PlayerInfo info : players) {
			Component display = info.getTabListDisplayName();
			if (display != null) {
				tab.add(display.getString());
			}
		}
		String area = findArea(tab);
		if (area != null) {
			return isMiningArea(area);
		}
		return sidebarMatches(minecraft.level.getScoreboard());
	}

	private static boolean sidebarMatches(Scoreboard scoreboard) {
		List<String> lines = new ArrayList<>();
		for (PlayerTeam team : scoreboard.getPlayerTeams()) {
			lines.add(team.getPlayerPrefix().getString() + String.join("", team.getPlayers())
					+ team.getPlayerSuffix().getString());
		}
		return sidebarContainsMiningArea(lines);
	}

	/** Returns the value after "Area:" from tab list lines, or null when no such line exists. */
	public static @Nullable String findArea(Iterable<String> tabLines) {
		for (String raw : tabLines) {
			String line = strip(raw);
			if (line.toLowerCase(Locale.ROOT).startsWith("area:")) {
				return line.substring("area:".length()).strip();
			}
		}
		return null;
	}

	public static boolean isMiningArea(String area) {
		String lower = strip(area).toLowerCase(Locale.ROOT);
		return lower.contains("dwarven mines") || lower.contains("crystal hollows");
	}

	public static boolean sidebarContainsMiningArea(Iterable<String> lines) {
		for (String line : lines) {
			if (isMiningArea(line)) {
				return true;
			}
		}
		return false;
	}

	/** Pure helper: tab list first, sidebar only when the tab list has no area line. */
	public static boolean detect(Iterable<String> tabLines, Iterable<String> sidebarLines) {
		String area = findArea(tabLines);
		return area != null ? isMiningArea(area) : sidebarContainsMiningArea(sidebarLines);
	}

	/** Removes section-sign formatting codes and surrounding whitespace. */
	public static String strip(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§') {
				i++;
				continue;
			}
			out.append(c);
		}
		return out.toString().strip();
	}
}
