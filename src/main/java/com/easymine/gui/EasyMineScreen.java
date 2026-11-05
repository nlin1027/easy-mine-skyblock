package com.easymine.gui;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.easymine.Settings;
import com.easymine.route.Recorder;
import com.easymine.route.Route;
import com.easymine.route.RoutePlayer;
import com.easymine.route.RouteStore;

/**
 * Floating, non-pausing control panel: record routes, pick the route to play and toggle repeat. Built only from plain
 * buttons and one text box; the route list is a simple paged list.
 */
public final class EasyMineScreen extends Screen {
	private static final int PANEL_WIDTH = 232;
	private static final int ROWS_PER_PAGE = 5;
	private static final int ROW_HEIGHT = 22;
	private static final int PANEL_HEIGHT = 262;

	private static int page;

	private @Nullable EditBox nameBox;
	private int left;
	private int top;

	private EasyMineScreen() {
		super(Component.translatable("easymine.screen.title"));
	}

	/** Opens the panel on the next client tick, so it is safe to call from a command or a button. */
	public static void open() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.schedule(() -> minecraft.gui.setScreen(new EasyMineScreen()));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		build();
	}

	private void build() {
		clearWidgets();
		left = (width - PANEL_WIDTH) / 2;
		top = Math.max(4, (height - PANEL_HEIGHT) / 2);
		int x = left + 8;
		int inner = PANEL_WIDTH - 16;
		int y = top + 20;

		String previous = nameBox != null ? nameBox.getValue() : Recorder.draftName();
		nameBox = new EditBox(font, x, y, inner, 18, Component.translatable("easymine.screen.name"));
		nameBox.setMaxLength(RouteStore.MAX_NAME_LENGTH);
		nameBox.setHint(Component.translatable("easymine.screen.name_hint"));
		nameBox.setValue(previous);
		nameBox.setResponder(Recorder::setDraftName);
		addRenderableWidget(nameBox);
		y += 22;

		boolean recording = Recorder.isRecording();
		addRenderableWidget(Button.builder(
				Component.translatable(recording ? "easymine.screen.stop_recording" : "easymine.screen.start_recording"),
				button -> toggleRecording()).bounds(x, y, inner, 20).build());
		y += 36;

		List<Route> routes = RouteStore.list();
		int pages = Math.max(1, (routes.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		page = Math.min(Math.max(page, 0), pages - 1);
		int first = page * ROWS_PER_PAGE;
		for (int i = first; i < Math.min(routes.size(), first + ROWS_PER_PAGE); i++) {
			Route route = routes.get(i);
			int rowY = y + (i - first) * ROW_HEIGHT;
			boolean selected = RoutePlayer.isSelected(route);
			addRenderableWidget(Button.builder(
					Component.translatable(selected ? "easymine.screen.deselect" : "easymine.screen.select"),
					button -> {
						RoutePlayer.toggle(route);
						build();
					}).bounds(x + inner - 112, rowY, 70, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("easymine.screen.delete"), button -> {
				delete(route);
				build();
			}).bounds(x + inner - 40, rowY, 40, 20).build());
		}
		int footerY = y + ROWS_PER_PAGE * ROW_HEIGHT + 2;
		Button prev = Button.builder(Component.literal("<"), button -> {
			page--;
			build();
		}).bounds(x, footerY, 20, 20).build();
		prev.active = page > 0;
		addRenderableWidget(prev);
		Button next = Button.builder(Component.literal(">"), button -> {
			page++;
			build();
		}).bounds(x + 24, footerY, 20, 20).build();
		next.active = page < pages - 1;
		addRenderableWidget(next);
		addRenderableWidget(Button.builder(repeatLabel(), button -> {
			RoutePlayer.setRepeat(!RoutePlayer.repeat());
			button.setMessage(repeatLabel());
		}).bounds(x + 52, footerY, inner - 52, 20).build());
		addRenderableWidget(Button.builder(playModeLabel(), button -> {
			Settings.setPlayMode(Settings.playMode().next());
			RoutePlayer.onPlayModeChanged();
			button.setMessage(playModeLabel());
		}).bounds(x, footerY + 24, inner, 20).build());
	}

	private static Component playModeLabel() {
		return Component.translatable("easymine.screen.play_mode",
				Component.translatable("easymine.screen.play_mode." + Settings.playMode().name().toLowerCase(java.util.Locale.ROOT)));
	}

	private static Component hintLabel() {
		return switch (Settings.playMode()) {
			case HOLD -> Component.translatable("easymine.screen.hint.hold");
			case KEYBIND -> Component.translatable("easymine.screen.hint.keybind", RoutePlayer.playKeyName());
			case BOTH -> Component.translatable("easymine.screen.hint.both", RoutePlayer.playKeyName());
		};
	}

	private static Component repeatLabel() {
		return Component.translatable(RoutePlayer.repeat() ? "easymine.screen.repeat_on" : "easymine.screen.repeat_off");
	}

	private void toggleRecording() {
		if (Recorder.isRecording()) {
			Recorder.stop();
		} else {
			String name = nameBox != null ? nameBox.getValue() : Recorder.draftName();
			Recorder.setDraftName(name);
			Component error = Recorder.nameError(name);
			if (error == null) {
				error = Recorder.start(name);
			}
			if (error != null) {
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.sendOverlayMessage(error);
				}
				return;
			}
			// Recording ignores input while a screen is open, so hand control back to the game.
			onClose();
			return;
		}
		build();
	}

	private void delete(Route route) {
		try {
			if (RoutePlayer.isSelected(route)) {
				RoutePlayer.stop();
			}
			RouteStore.delete(route);
		} catch (IOException e) {
			if (minecraft != null && minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.translatable("easymine.command.delete_failed", route.name()));
			}
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Floating panel: no dimming and no blur, the world stays visible.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xD0101010);
		graphics.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF808080);
		graphics.text(font, title, left + 8, top + 7, 0xFFFFFFFF);

		int x = left + 8;
		int y = top + 66;
		Component status;
		if (Recorder.isRecording()) {
			status = Component.translatable("easymine.screen.status.recording", Recorder.activeName(),
					Route.formatDuration(Recorder.elapsedTicks()));
		} else if (Recorder.lastResult() != null) {
			status = Recorder.lastResult();
		} else {
			status = Component.translatable("easymine.screen.status.idle");
		}
		graphics.text(font, status, x, y, 0xFFFFD700);

		List<Route> routes = RouteStore.list();
		int rowsTop = y + 12;
		if (routes.isEmpty()) {
			graphics.text(font, Component.translatable("easymine.screen.no_routes"), x, rowsTop + 6, 0xFFAAAAAA);
		}
		int first = page * ROWS_PER_PAGE;
		for (int i = first; i < Math.min(routes.size(), first + ROWS_PER_PAGE); i++) {
			Route route = routes.get(i);
			boolean selected = RoutePlayer.isSelected(route);
			String label = font.plainSubstrByWidth(route.name(), 96);
			int rowY = rowsTop + (i - first) * ROW_HEIGHT + 6;
			graphics.text(font, Component.literal((selected ? "● " : "") + label), x, rowY,
					selected ? 0xFF55FF55 : 0xFFFFFFFF);
		}
		graphics.text(font, hintLabel(), x, top + PANEL_HEIGHT - 12, 0xFFAAAAAA);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
