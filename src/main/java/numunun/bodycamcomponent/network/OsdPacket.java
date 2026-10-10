package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// 서버 → 클라이언트: 녹화 장비 화면 표시 (PAUSE / PLAY / STOP)
public record OsdPacket(int kind, String sub, int seconds) {
    public static final int HIDE = 0;
    public static final int PAUSE = 1;
    public static final int PLAY = 2;
    public static final int STOP = 3;

    public static void encode(OsdPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kind);
        buf.writeUtf(msg.sub);
        buf.writeVarInt(msg.seconds);
    }

    public static OsdPacket decode(FriendlyByteBuf buf) {
        return new OsdPacket(buf.readVarInt(), buf.readUtf(), buf.readVarInt());
    }

    public static void handle(OsdPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> OsdClientData.show(msg));
        ctx.get().setPacketHandled(true);
    }
}