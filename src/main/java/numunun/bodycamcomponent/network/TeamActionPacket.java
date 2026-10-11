package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import numunun.bodycamcomponent.game.TeamBalancer;
import numunun.bodycamcomponent.util.Safe;

import java.util.UUID;
import java.util.function.Supplier;

// 클라이언트 → 서버: 팀 배정 화면 조작 (수정 권한은 서버에서 확인)
public record TeamActionPacket(int action, UUID target) {
    public static final int OPEN = 0;
    public static final int MOVE = 1;
    public static final int AUTO = 2;
    public static final int SHUFFLE = 3;
    public static final int CONFIRM = 4;
    public static final int CLOSE = 5;

    public static final UUID NONE = new UUID(0L, 0L);

    public static void encode(TeamActionPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action);
        buf.writeUUID(msg.target);
    }

    public static TeamActionPacket decode(FriendlyByteBuf buf) {
        return new TeamActionPacket(buf.readVarInt(), buf.readUUID());
    }

    public static void handle(TeamActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null) Safe.run("팀 배정 화면", () -> TeamBalancer.handle(sender, msg.action(), msg.target()));
        });
        ctx.get().setPacketHandled(true);
    }
}