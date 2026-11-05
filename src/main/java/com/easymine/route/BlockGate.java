package com.easymine.route;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.player.LocalPlayer;

/**
 * Helpers for a route's "only play while standing on this block" setting.
 *
 * <p>The client always knows the block under the player: the server sends the full block states of every loaded chunk and
 * the player is always inside one. Nothing is requested from the server and nothing is sent.
 */
public final class BlockGate {
	private BlockGate() {
	}

	/**
	 * The block that is holding the player up, or null while airborne, swimming or without a player. This is the block the
	 * game itself treats as "the one you stand on", so a slab or carpet counts as that slab or carpet, not the block below.
	 */
	public static @Nullable Identifier standingOn(@Nullable LocalPlayer player) {
		if (player == null || !player.onGround()) {
			return null;
		}
		Block block = player.level().getBlockState(player.getOnPos()).getBlock();
		return BuiltInRegistries.BLOCK.getKey(block);
	}

	/**
	 * Parses {@code gold_block} or {@code minecraft:gold_block}. Returns null if it is not a real, placeable block (air and
	 * its variants are rejected, since nobody can stand on them).
	 */
	public static @Nullable Identifier parse(String text) {
		Identifier id = Identifier.tryParse(text.strip().toLowerCase(java.util.Locale.ROOT));
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getValue(id).defaultBlockState().isAir() ? null : id;
	}

	/** The block's name in the player's language, or the raw id if no such block is registered. */
	public static Component name(Identifier id) {
		return BuiltInRegistries.BLOCK.containsKey(id)
				? BuiltInRegistries.BLOCK.getValue(id).getName()
				: Component.literal(id.toString());
	}

	/**
	 * Saves a new stand-on setting for {@code route} and tells the player so a selected route picks it up straight away.
	 *
	 * @param block the block to require, or null to play anywhere
	 * @return the updated route
	 */
	public static Route configure(Route route, @Nullable Identifier block, Route.BlockCheck check) throws IOException {
		Route updated = route.withStandOn(block, check);
		RouteStore.update(updated);
		RoutePlayer.onRouteChanged(updated);
		return updated;
	}
}
