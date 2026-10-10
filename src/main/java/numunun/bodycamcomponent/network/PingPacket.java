package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// 서버 → 클라이언트: 팀원의 핑
public record PingPacket(String sender, double x, double y, double z, boolean enemy, int lifetimeMs) {

    public static void encode(PingPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sender);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeBoolean(msg.enemy);
        buf.writeVarInt(msg.lifetimeMs);
    }

    public static PingPacket decode(FriendlyByteBuf buf) {
        return new PingPacket(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(PingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> PingClientData.add(msg));
        ctx.get().setPacketHandled(true);
    }
}