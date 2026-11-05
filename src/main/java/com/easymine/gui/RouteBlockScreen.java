package com.easymine.gui;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.easymine.route.BlockGate;
import com.easymine.route.Route;

/**
 * Per-route settings for "only play while standing on this block". Like the main panel it floats over the world without
 * pausing, so the player keeps standing where they are and "use the block I'm standing on" reads the right block.
 */
public final class RouteBlockScreen extends Screen {
	private static final int PANEL_WIDTH = 232;
	private static final int PANEL_HEIGHT = 154;

	private Route route;
	private int left;
	private int top;
	private @Nullable Component message;

	private RouteBlockScreen(Route route) {
		super(Component.translatable("easymine.block.title"));
		this.route = route;
	}

	/** Opens the panel on the next client tick, so it is safe to call from a button. */
	public static void open(Route route) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.schedule(() -> minecraft.gui.setScreen(new RouteBlockScreen(route)));
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
		int y = top + 58;
		boolean restricted = route.standOn() != null;

		addRenderableWidget(Button.builder(Component.translatable("easymine.block.here"), button -> useCurrentBlock())
				.bounds(x, y, inner, 20).build());
		y += 24;

		Button check = Button.builder(checkLabel(), button -> save(route.standOn(), route.standOnCheck().next()))
				.bounds(x, y, inner, 20)
				.tooltip(Tooltip.create(Component.translatable(route.standOnCheck() == Route.BlockCheck.ALWAYS
						? "easymine.block.check_always.tooltip" : "easymine.block.check_start.tooltip")))
				.build();
		check.active = restricted;
		addRenderableWidget(check);
		y += 24;

		Button clear = Button.builder(Component.translatable("easymine.block.clear"), button -> save(null, route.standOnCheck()))
				.bounds(x, y, inner, 20).build();
		clear.active = restricted;
		addRenderableWidget(clear);
		y += 24;

		addRenderableWidget(Button.builder(Component.translatable("easymine.block.back"), button -> EasyMineScreen.open())
				.bounds(x, y, inner, 20).build());
	}

	private Component checkLabel() {
		return Component.translatable(route.standOnCheck() == Route.BlockCheck.ALWAYS
				? "easymine.block.check_always" : "easymine.block.check_start");
	}

	private void useCurrentBlock() {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		Identifier here = BlockGate.standingOn(player);
		if (here == null) {
			message = Component.translatable("easymine.block.not_grounded");
			return;
		}
		save(here, route.standOnCheck());
	}

	private void save(@Nullable Identifier block, Route.BlockCheck check) {
		try {
			route = BlockGate.configure(route, block, check);
			message = null;
		} catch (IOException e) {
			message = Component.translatable("easymine.block.save_failed", route.name());
		}
		build();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Floating panel: no dimming and no blur, the world stays visible.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xD0101010);
		graphics.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF808080);
		int x = left + 8;
		graphics.text(font, title, x, top + 7, 0xFFFFFFFF);
		graphics.text(font, Component.literal(font.plainSubstrByWidth(route.name(), PANEL_WIDTH - 16)), x, top + 20, 0xFFAAAAAA);
		Identifier required = route.standOn();
		Component status = required == null
				? Component.translatable("easymine.block.any")
				: Component.translatable("easymine.block.current", BlockGate.name(required));
		graphics.text(font, status, x, top + 34, 0xFFFFD700);
		if (message != null) {
			graphics.text(font, message, x, top + 46, 0xFFFF5555);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
