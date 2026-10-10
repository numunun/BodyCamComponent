package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// 서버 → 클라이언트: 경기 시작 연출 (암전 시간 + 소속/팀/직급/이름)
public record IntroPacket(String unitLine, String teamLine, int teamColor, String role, String name, int blackTicks) {

    public static void encode(IntroPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.unitLine);
        buf.writeUtf(msg.teamLine);
        buf.writeInt(msg.teamColor);
        buf.writeUtf(msg.role);
        buf.writeUtf(msg.name);
        buf.writeVarInt(msg.blackTicks);
    }

    public static IntroPacket decode(FriendlyByteBuf buf) {
        return new IntroPacket(buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readUtf(), buf.readUtf(), buf.readVarInt());
    }

    public static void handle(IntroPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> IntroClientData.start(msg));
        ctx.get().setPacketHandled(true);
    }
}