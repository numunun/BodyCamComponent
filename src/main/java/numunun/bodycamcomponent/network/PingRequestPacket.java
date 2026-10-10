package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import numunun.bodycamcomponent.game.PingManager;

import java.util.function.Supplier;

// 클라이언트 → 서버: 핑 요청 (entityId는 가리킨 플레이어, 없으면 -1)
public record PingRequestPacket(double x, double y, double z, int entityId) {

    public static void encode(PingRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeVarInt(msg.entityId + 1);
    }

    public static PingRequestPacket decode(FriendlyByteBuf buf) {
        return new PingRequestPacket(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt() - 1);
    }

    public static void handle(PingRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null) PingManager.handle(sender, msg);
        });
        ctx.get().setPacketHandled(true);
    }
}