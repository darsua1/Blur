package com.blur.client.mixin;

import com.blur.client.BlurClient;
import com.blur.client.Diagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects totem-of-undying pops at the packet level.
 *
 * <p>When a totem procs, the server broadcasts a {@link ClientboundEntityEventPacket}
 * with event id 35 (confirmed in 1.21.11: {@code LivingEntity.checkTotemDeathProtection}
 * calls {@code Level.broadcastEntityEvent(this, (byte)35)}). We hook the client's
 * packet handler directly rather than {@code LivingEntity.handleEntityEvent}, because
 * in 1.21.11 the totem is dispatched through the Player/Avatar subclass chain and never
 * reaches {@code LivingEntity.handleEntityEvent} — which is why the old hook saw nothing.
 * Hooking the packet entry point catches it no matter how it's later dispatched.
 *
 * <p>{@link Diagnostics} counters here let the support log prove whether these packets
 * arrive at all (some anticheats strip them).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	/** Only count totems popped this close to you (blocks). */
	private static final double RANGE = 48.0;

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void blur$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		// Packet handlers are first invoked on the netty thread (which reschedules to
		// the main thread); only count the main-thread pass so we don't double-count.
		if (!mc.isSameThread()) {
			return;
		}
		Diagnostics.entityEventSeen();
		if (packet.getEventId() != 35) {
			return; // 35 = totem of undying pop
		}
		Diagnostics.totemEventSeen();
		if (mc.player == null || mc.level == null) {
			return;
		}
		Entity e = packet.getEntity(mc.level);
		if (e == mc.player) {
			BlurClient.onOwnTotem(); // your own totem = "used", not "forced"
			return;
		}
		if (!(e instanceof LivingEntity)) {
			return;
		}
		if (e.distanceToSqr(mc.player) > RANGE * RANGE) {
			return; // someone else's fight across the map
		}
		BlurClient.onOpponentTotem(e.getId());
	}
}
