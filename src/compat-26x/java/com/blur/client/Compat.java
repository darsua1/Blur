package com.blur.client;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Version bridge for Minecraft 26.1 / 26.1.1 / 26.1.2 / 26.2 (unobfuscated).
 *
 * <p>Everything else in Blur compiles unchanged across 1.21.11 and 26.x; only these
 * two APIs actually differ, so they live here instead of forcing a preprocessor or
 * reflection on the whole codebase. The 1.21.11 twin is in {@code src/compat-1211}.
 *
 * <ul>
 *   <li>Keybind registration moved to {@code fabric-key-mapping-api-v1}:
 *       {@code KeyMappingHelper.registerKeyMapping}.</li>
 *   <li>{@code Player.displayClientMessage(Component, boolean)} was split into
 *       {@code sendSystemMessage} (chat) and {@code sendOverlayMessage} (action bar).</li>
 * </ul>
 */
public final class Compat {
	private Compat() {
	}

	public static KeyMapping registerKey(KeyMapping mapping) {
		return KeyMappingHelper.registerKeyMapping(mapping);
	}

	/** Normal chat line. */
	public static void chat(Player player, Component message) {
		player.sendSystemMessage(message);
	}

	/** Above the hotbar. */
	public static void actionBar(Player player, Component message) {
		player.sendOverlayMessage(message);
	}
}
