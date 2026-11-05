package com.easymine.command;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import com.easymine.gui.EasyMineScreen;
import com.easymine.route.BlockGate;
import com.easymine.route.Recorder;
import com.easymine.route.Route;
import com.easymine.route.RoutePlayer;
import com.easymine.route.RouteStore;

/** The client-side /em command. Works anywhere, not only in SkyBlock. */
public final class EasyMineCommand {
	private static final String[] HELP_KEYS = {"title", "open", "record", "stop", "status", "list", "select", "deselect", "delete", "block"};

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
				ctx.getSource().sendFeedback(route.standOn() == null
						? Component.translatable("easymine.command.list.entry",
								route.name(), Route.formatDuration(route.lengthTicks()), route.inputCount())
						: Component.translatable("easymine.command.list.entry_block",
								route.name(), Route.formatDuration(route.lengthTicks()), route.inputCount(),
								BlockGate.name(route.standOn())));
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
		root.then(buildBlockCommand());
		dispatcher.register(root);
	}

	/**
	 * {@code /em block here|set|clear|check ...}: only play a route while standing on a block. The route name is always the
	 * last argument so it can contain spaces.
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> buildBlockCommand() {
		LiteralArgumentBuilder<FabricClientCommandSource> block = ClientCommands.literal("block");
		block.then(ClientCommands.literal("here").then(routeArgument().executes(ctx -> {
			Route route = requireRoute(ctx);
			if (route == null) {
				return 0;
			}
			Identifier here = BlockGate.standingOn(Minecraft.getInstance().player);
			if (here == null) {
				ctx.getSource().sendError(Component.translatable("easymine.block.not_grounded"));
				return 0;
			}
			return setBlock(ctx, route, here, route.standOnCheck());
		})));
		block.then(ClientCommands.literal("set")
				.then(ClientCommands.argument("block", StringArgumentType.word())
						.suggests((ctx, builder) -> suggestBlocks(builder))
						.then(routeArgument().executes(ctx -> {
							Route route = requireRoute(ctx);
							if (route == null) {
								return 0;
							}
							String text = StringArgumentType.getString(ctx, "block");
							Identifier id = BlockGate.parse(text);
							if (id == null) {
								ctx.getSource().sendError(Component.translatable("easymine.block.unknown_block", text));
								return 0;
							}
							return setBlock(ctx, route, id, route.standOnCheck());
						}))));
		block.then(ClientCommands.literal("clear").then(routeArgument().executes(ctx -> {
			Route route = requireRoute(ctx);
			return route == null ? 0 : setBlock(ctx, route, null, route.standOnCheck());
		})));
		LiteralArgumentBuilder<FabricClientCommandSource> check = ClientCommands.literal("check");
		for (Route.BlockCheck mode : Route.BlockCheck.values()) {
			check.then(ClientCommands.literal(mode.name().toLowerCase(Locale.ROOT)).then(routeArgument().executes(ctx -> {
				Route route = requireRoute(ctx);
				if (route == null) {
					return 0;
				}
				if (route.standOn() == null) {
					ctx.getSource().sendError(Component.translatable("easymine.block.no_block_set", route.name()));
					return 0;
				}
				return setBlock(ctx, route, route.standOn(), mode);
			})));
		}
		block.then(check);
		return block;
	}

	private static RequiredArgumentBuilder<FabricClientCommandSource, String> routeArgument() {
		return ClientCommands.argument("name", StringArgumentType.greedyString()).suggests((ctx, builder) -> suggestNames(builder));
	}

	private static int setBlock(CommandContext<FabricClientCommandSource> ctx, Route route, @Nullable Identifier block,
			Route.BlockCheck check) {
		try {
			Route updated = BlockGate.configure(route, block, check);
			ctx.getSource().sendFeedback(updated.standOn() == null
					? Component.translatable("easymine.block.cleared", updated.name())
					: Component.translatable("easymine.block.saved", updated.name(), BlockGate.name(updated.standOn()),
							Component.translatable(check == Route.BlockCheck.ALWAYS
									? "easymine.block.check_always.short" : "easymine.block.check_start.short")));
			return 1;
		} catch (IOException e) {
			ctx.getSource().sendError(Component.translatable("easymine.block.save_failed", route.name()));
			return 0;
		}
	}

	private static CompletableFuture<Suggestions> suggestBlocks(SuggestionsBuilder builder) {
		String typed = builder.getRemainingLowerCase();
		for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
			String full = id.toString();
			String shown = "minecraft".equals(id.getNamespace()) ? id.getPath() : full;
			if (full.startsWith(typed) || shown.startsWith(typed)) {
				builder.suggest(shown);
			}
		}
		return builder.buildFuture();
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
