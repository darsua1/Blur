package com.blur.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Client entrypoint. Wires the version-specific detection hooks to the
 * version-independent {@link StatEngine}, and starts the {@link WebServer} that
 * feeds the dashboard.
 *
 * <p>Crystal-placement detection is a two-step correlation so we only count
 * <em>our own</em> crystals, not the opponent's: a right-click while holding an
 * End Crystal records a "pending" placement at the target block; when an
 * {@link EndCrystal} entity then loads near that spot within a short window, we
 * count it. Position + time matching keeps the opponent's crystals out.
 */
public class BlurClient implements ClientModInitializer {

	/** How long after a right-click a spawned crystal still counts as ours. */
	private static final long CRYSTAL_MATCH_WINDOW_MS = 800L;
	/** Squared distance (blocks^2) between click spot and crystal to still match. */
	private static final double CRYSTAL_MATCH_DIST_SQ = 4.0;

	private static StatEngine engine;
	private static WebServer webServer;

	/** Opponent scan radius: 2 chunks. */
	private static final double OPPONENT_RANGE = 32.0;
	/** Scan every N client ticks (20 ticks = 1s), cheap enough to be free. */
	private static final int SCAN_INTERVAL = 10;

	private final Deque<Pending> pending = new ArrayDeque<>();
	private boolean announced = false;
	private int scanTick = 0;

	@Override
	public void onInitializeClient() {
		engine = new StatEngine();
		webServer = new WebServer(engine);
		webServer.start();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath("blur", "main"));

		KeyMapping sessionKey = Compat.registerKey(new KeyMapping(
				"key.blur.session", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));

		KeyMapping urlKey = Compat.registerKey(new KeyMapping(
				"key.blur.dashboard", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, category));

		registerPlacementHooks();

		// Flush records when the game closes, so nothing is lost if the player
		// quits without pressing stop.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> engine.shutdown());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Announce the dashboard URL once, after joining a world.
			if (!announced && client.player != null && webServer.getUrl() != null) {
				announced = true;
				Compat.chat(client.player, Component.literal(
						"[Blur] Live stats: " + webServer.getUrl()));
			}

			while (sessionKey.consumeClick()) {
				boolean on = engine.toggleSession();
				if (client.player != null) {
					Compat.actionBar(client.player, Component.literal(
							"Blur: measuring " + (on ? "STARTED" : "STOPPED")));
				}
			}

			while (urlKey.consumeClick()) {
				Path log = Diagnostics.writeReport(engine, webServer);
				if (client.player != null) {
					String url = webServer.getUrl();
					Compat.chat(client.player, Component.literal(
							"[Blur] Connection: " + (url == null ? "not running" : url)));
					Compat.chat(client.player, Component.literal(log != null
							? "[Blur] Support log saved to logs/BlurLog.txt - attach it to your Discord ticket."
							: "[Blur] Could not write the support log (see the game log)."));
				}
			}

			// Keep the app's "press X to measure" hint correct if the key is rebound.
			engine.setKeyLabel(sessionKey.getTranslatedKeyMessage().getString());

			scanForOpponents(client);
			engine.tickPersist();
		});
	}

	private void registerPlacementHooks() {
		// Right-click on a block: note crystal-item uses and anchor interactions.
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			Minecraft mc = Minecraft.getInstance();
			if (world.isClientSide() && player == mc.player) {
				BlockPos pos = hit.getBlockPos();
				BlockState state = world.getBlockState(pos);
				ItemStack stack = player.getItemInHand(hand);
				// Count only respawn-anchor DETONATIONS you trigger -- not charging
				// clicks and not the spam-clicking that used to inflate the number.
				// A detonation = a charged anchor, in a dimension where it explodes
				// (overworld / end), on a click that isn't adding another charge.
				if (state.getBlock() instanceof RespawnAnchorBlock) {
					int charge = state.getValue(RespawnAnchorBlock.CHARGE);
					// Anchors set spawn (don't explode) only in the Nether; everywhere
					// else a charged anchor detonates when used.
					boolean explodes = charge > 0 && world.dimension() != Level.NETHER;
					boolean chargingClick = stack.is(Items.GLOWSTONE) && charge < 4;
					if (explodes && !chargingClick) {
						engine.recordAnchor();
					}
				}
				if (stack.is(Items.END_CRYSTAL)) {
					// Crystal spawns ~1 block above the clicked block.
					synchronized (pending) {
						pending.addLast(new Pending(System.currentTimeMillis(),
								pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
						prunePending(System.currentTimeMillis());
					}
				}
			}
			return InteractionResult.PASS; // observe only, never change behaviour
		});

		// A crystal entity appeared: if it matches a recent click of ours, count it.
		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (!(entity instanceof EndCrystal)) {
				return;
			}
			long now = System.currentTimeMillis();
			Vec3 p = entity.position();
			synchronized (pending) {
				prunePending(now);
				Iterator<Pending> it = pending.iterator();
				while (it.hasNext()) {
					Pending pd = it.next();
					double dx = p.x - pd.x(), dy = p.y - pd.y(), dz = p.z - pd.z();
					if (dx * dx + dy * dy + dz * dz <= CRYSTAL_MATCH_DIST_SQ) {
						it.remove();
						engine.recordCrystal();
						break;
					}
				}
			}
		});
	}

	/**
	 * While measuring, note every player within {@value #OPPONENT_RANGE} blocks
	 * (~2 chunks). Counting sightings over the whole duel means the person you
	 * actually fought outranks anyone who briefly ran past.
	 */
	private void scanForOpponents(Minecraft client) {
		if (!engine.isActive() || client.level == null || client.player == null) {
			return;
		}
		if (++scanTick < SCAN_INTERVAL) {
			return;
		}
		scanTick = 0;
		for (Player p : client.level.players()) {
			if (p == client.player) continue;
			if (p.distanceToSqr(client.player) <= OPPONENT_RANGE * OPPONENT_RANGE) {
				engine.noteNearbyPlayer(p.getName().getString());
			}
		}
	}

	private void prunePending(long now) {
		while (!pending.isEmpty() && now - pending.peekFirst().time() > CRYSTAL_MATCH_WINDOW_MS) {
			pending.pollFirst();
		}
	}

	/** Called from {@link com.blur.client.mixin.ClientPacketListenerMixin}. */
	public static void onOpponentTotem(int entityId) {
		if (engine != null) {
			engine.recordOpponentTotem(entityId);
		}
	}

	/** Called when the local player pops a totem (totems "used"). */
	public static void onOwnTotem() {
		if (engine != null) {
			engine.recordOwnTotem();
		}
	}

	private record Pending(long time, double x, double y, double z) {
	}
}
