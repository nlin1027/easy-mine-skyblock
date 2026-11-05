package com.easymine;

import net.fabricmc.api.ClientModInitializer;

import com.easymine.command.EasyMineCommand;
import com.easymine.gui.InventoryButtons;
import com.easymine.route.Recorder;
import com.easymine.route.RoutePlayer;

/**
 * Client entrypoint. Easy Mine is purely client-side: it never registers a network channel, sends a packet or contacts
 * any web API. The only thing it reads from the server is what the client already holds (the tab list).
 */
public final class EasyMineClient implements ClientModInitializer {
	public static final String MOD_ID = "easymine";

	@Override
	public void onInitializeClient() {
		Settings.load();
		EasyMineCommand.register();
		InventoryButtons.register();
		Recorder.register();
		RoutePlayer.register();
	}
}
