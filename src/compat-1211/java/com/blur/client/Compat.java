package com.blur.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Version bridge for Minecraft 1.21.11.
 *
 * <p>Everything else in Blur compiles unchanged across 1.21.11 and 26.x; only these
 * two APIs actually differ, so they live here instead of forcing a preprocessor or
 * reflection on the whole codebase. The 26.x twin is in {@code src/compat-26x}.
 *
 * <ul>
 *   <li>Keybind registration: {@code fabric-key-binding-api-v1} / {@code KeyBindingHelper}
 *       (renamed to {@code fabric-key-mapping-api-v1} / {@code KeyMappingHelper} in 26.x).</li>
 *   <li>Messages: {@code Player.displayClientMessage(Component, boolean)}
 *       (split into {@code sendSystemMessage} / {@code sendOverlayMessage} in 26.x).</li>
 * </ul>
 */
public final class Compat {
	private Compat() {
	}

	public static KeyMapping registerKey(KeyMapping mapping) {
		return KeyBindingHelper.registerKeyBinding(mapping);
	}

	/** Normal chat line. */
	public static void chat(Player player, Component message) {
		player.displayClientMessage(message, false);
	}

	/** Above the hotbar. */
	public static void actionBar(Player player, Component message) {
		player.displayClientMessage(message, true);
	}
}
