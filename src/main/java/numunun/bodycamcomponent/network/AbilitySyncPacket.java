package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// 서버 → 클라이언트: 내 직급, 소속 팀(오른쪽 위 표시용), 직급별 화면 능력
public record AbilitySyncPacket(String role, String team, int teamColor, boolean seeAlliesThroughWalls) {

    public static void encode(AbilitySyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.role);
        buf.writeUtf(msg.team);
        buf.writeInt(msg.teamColor);
        buf.writeBoolean(msg.seeAlliesThroughWalls);
    }

    public static AbilitySyncPacket decode(FriendlyByteBuf buf) {
        return new AbilitySyncPacket(buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readBoolean());
    }

    public static void handle(AbilitySyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            AbilityClientData.role = msg.role();
            AbilityClientData.team = msg.team();
            AbilityClientData.teamColor = msg.teamColor();
            AbilityClientData.seeAlliesThroughWalls = msg.seeAlliesThroughWalls();
        });
        ctx.get().setPacketHandled(true);
    }
}