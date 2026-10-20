package dev.advik.mcdt.mixin;

import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.reflection.NameService;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@Shadow public abstract PacketFlow getReceiving();

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void mcdt$onChannelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (getReceiving() == PacketFlow.CLIENTBOUND) {
			String packetName = NameService.getInstance().simpleClassName(packet.getClass());
			EventHub.getInstance().publish("packet.in", Map.of(
					"packet", packetName
			));
		}
	}
}
