package com.easymine.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import com.easymine.route.Route;
import com.easymine.route.RoutePlayer;
import com.easymine.route.RouteStore;
import com.easymine.skyblock.SkyblockLocation;

/**
 * Side buttons drawn left of the survival/creative inventory: an EM button that opens the panel, one toggle per saved
 * route (green marker when selected) and a Stop button. Only visible in the Dwarven Mines or Crystal Hollows.
 *
 * <p>The buttons are drawn and hit-tested by hand, so they follow the inventory panel and need no widget internals.
 */
public final class InventoryButtons {
	private static final int WIDTH = 72;
	private static final int HEIGHT = 14;
	private static final int GAP = 2;
	private static final int RECIPE_BOOK_WIDTH = 147;

	private record Entry(Component label, boolean selected, Runnable action) {
	}

	private InventoryButtons() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) {
				return;
			}
			AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
					draw(client, container, graphics, mouseX, mouseY));
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(client, container, event.x(), event.y(), event.button()));
		});
	}

	private static int originX(AbstractContainerScreen<?> screen) {
		int x = screen.leftPos;
		boolean bookOpen = screen instanceof InventoryScreen && x != (screen.width - screen.imageWidth) / 2;
		return x - GAP * 2 - WIDTH - (bookOpen ? RECIPE_BOOK_WIDTH : 0);
	}

	private static List<Entry> entries(Screen screen) {
		List<Entry> entries = new ArrayList<>();
		entries.add(new Entry(Component.translatable("easymine.button.open"), false, EasyMineScreen::open));
		for (Route route : RouteStore.list()) {
			boolean selected = RoutePlayer.isSelected(route);
			entries.add(new Entry(Component.literal(route.name()), selected, () -> RoutePlayer.toggle(route)));
		}
		entries.add(new Entry(Component.translatable("easymine.button.stop"), false, RoutePlayer::stop));
		return entries;
	}

	private static void draw(Minecraft client, AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
			int mouseX, int mouseY) {
		if (!SkyblockLocation.isInMiningArea()) {
			return;
		}
		Font font = client.font;
		int x = originX(screen);
		int y = screen.topPos;
		for (Entry entry : entries(screen)) {
			if (y + HEIGHT > screen.height) {
				break;
			}
			boolean hover = mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
			graphics.fill(x, y, x + WIDTH, y + HEIGHT, hover ? 0xE0404040 : 0xC0101010);
			graphics.outline(x, y, WIDTH, HEIGHT, entry.selected() ? 0xFF55FF55 : 0xFF808080);
			String text = font.plainSubstrByWidth(entry.label().getString(), WIDTH - 14);
			if (entry.selected()) {
				graphics.text(font, Component.literal("●"), x + 3, y + 3, 0xFF55FF55);
			}
			graphics.text(font, Component.literal(text), x + 11, y + 3, 0xFFFFFFFF);
			y += HEIGHT + GAP;
		}
	}

	/** Returns true when the click landed on one of our buttons (and ran its action). */
	private static boolean click(Minecraft client, AbstractContainerScreen<?> screen, double mouseX, double mouseY, int button) {
		if (button != 0 || !SkyblockLocation.isInMiningArea()) {
			return false;
		}
		int x = originX(screen);
		int y = screen.topPos;
		for (Entry entry : entries(screen)) {
			if (y + HEIGHT > screen.height) {
				break;
			}
			if (mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT) {
				entry.action().run();
				return true;
			}
			y += HEIGHT + GAP;
		}
		return false;
	}
}
