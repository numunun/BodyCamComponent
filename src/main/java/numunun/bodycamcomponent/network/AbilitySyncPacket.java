package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// 서버 → 클라이언트: 내 직급의 화면 관련 능력
public record AbilitySyncPacket(boolean seeAlliesThroughWalls) {

    public static void encode(AbilitySyncPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.seeAlliesThroughWalls);
    }

    public static AbilitySyncPacket decode(FriendlyByteBuf buf) {
        return new AbilitySyncPacket(buf.readBoolean());
    }

    public static void handle(AbilitySyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> AbilityClientData.seeAlliesThroughWalls = msg.seeAlliesThroughWalls());
        ctx.get().setPacketHandled(true);
    }
}