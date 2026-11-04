package com.easymine.command;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import com.easymine.gui.EasyMineScreen;
import com.easymine.route.Recorder;
import com.easymine.route.Route;
import com.easymine.route.RoutePlayer;
import com.easymine.route.RouteStore;

/** The client-side /em command. Works anywhere, not only in SkyBlock. */
public final class EasyMineCommand {
	private static final String[] HELP_KEYS = {"title", "open", "record", "stop", "status", "list", "select", "deselect", "delete"};

	private EasyMineCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("em").executes(ctx -> {
			EasyMineScreen.open();
			return 1;
		});
		root.then(ClientCommands.literal("help").executes(ctx -> {
			for (String key : HELP_KEYS) {
				ctx.getSource().sendFeedback(Component.translatable("easymine.command.help." + key));
			}
			return 1;
		}));
		root.then(ClientCommands.literal("record")
				.then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
					Component error = Recorder.start(StringArgumentType.getString(ctx, "name"));
					if (error != null) {
						ctx.getSource().sendError(error);
						return 0;
					}
					return 1;
				})));
		root.then(ClientCommands.literal("stop").executes(ctx -> {
			if (Recorder.isRecording()) {
				Recorder.stop();
				Component result = Recorder.lastResult();
				if (result != null) {
					ctx.getSource().sendFeedback(result);
				}
			} else {
				RoutePlayer.stop();
				ctx.getSource().sendFeedback(Component.translatable("easymine.command.stopped"));
			}
			return 1;
		}));
		root.then(ClientCommands.literal("status").executes(ctx -> {
			FabricClientCommandSource source = ctx.getSource();
			if (Recorder.isRecording()) {
				source.sendFeedback(Component.translatable("easymine.command.status.recording",
						Recorder.activeName(), Route.formatDuration(Recorder.elapsedTicks())));
			} else {
				source.sendFeedback(Component.translatable("easymine.command.status.not_recording"));
			}
			Route selected = RoutePlayer.selected();
			source.sendFeedback(selected == null
					? Component.translatable("easymine.command.status.no_selection")
					: Component.translatable("easymine.command.status.selected", selected.name()));
			source.sendFeedback(Component.translatable(RoutePlayer.repeat()
					? "easymine.command.status.repeat_on" : "easymine.command.status.repeat_off"));
			return 1;
		}));
		root.then(ClientCommands.literal("list").executes(ctx -> {
			List<Route> routes = RouteStore.list();
			if (routes.isEmpty()) {
				ctx.getSource().sendFeedback(Component.translatable("easymine.command.list.empty"));
				return 0;
			}
			for (Route route : routes) {
				ctx.getSource().sendFeedback(Component.translatable("easymine.command.list.entry",
						route.name(), Route.formatDuration(route.lengthTicks()), route.inputCount()));
			}
			return routes.size();
		}));
		root.then(ClientCommands.literal("select")
				.then(ClientCommands.argument("name", StringArgumentType.greedyString())
						.suggests((ctx, builder) -> suggestNames(builder))
						.executes(ctx -> {
							Route route = requireRoute(ctx);
							return route != null && RoutePlayer.select(route) ? 1 : 0;
						})));
		root.then(ClientCommands.literal("deselect").executes(ctx -> {
			RoutePlayer.stop();
			return 1;
		}));
		root.then(ClientCommands.literal("delete")
				.then(ClientCommands.argument("name", StringArgumentType.greedyString())
						.suggests((ctx, builder) -> suggestNames(builder))
						.executes(ctx -> {
							Route route = requireRoute(ctx);
							if (route == null) {
								return 0;
							}
							try {
								if (RoutePlayer.isSelected(route)) {
									RoutePlayer.stop();
								}
								RouteStore.delete(route);
							} catch (IOException e) {
								ctx.getSource().sendError(Component.translatable("easymine.command.delete_failed", route.name()));
								return 0;
							}
							ctx.getSource().sendFeedback(Component.translatable("easymine.command.deleted", route.name()));
							return 1;
						})));
		dispatcher.register(root);
	}

	private static Route requireRoute(CommandContext<FabricClientCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		Route route = RouteStore.find(name);
		if (route == null) {
			ctx.getSource().sendError(Component.translatable("easymine.command.unknown_route", name));
		}
		return route;
	}

	private static CompletableFuture<Suggestions> suggestNames(SuggestionsBuilder builder) {
		String typed = builder.getRemainingLowerCase();
		for (Route route : RouteStore.list()) {
			if (route.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(route.name());
			}
		}
		return builder.buildFuture();
	}
}
