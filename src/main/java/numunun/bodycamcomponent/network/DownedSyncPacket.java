package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import numunun.bodycamcomponent.downed.DownedManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// 서버 → 클라이언트: 현재 빈사 상태인 플레이어 목록
public record DownedSyncPacket(List<UUID> downed) {

    public static void encode(DownedSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.downed.size());
        for (UUID id : msg.downed) buf.writeUUID(id);
    }

    public static DownedSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<UUID> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUUID());
        return new DownedSyncPacket(list);
    }

    public static void handle(DownedSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DownedManager.setClientDowned(msg.downed()));
        ctx.get().setPacketHandled(true);
    }
}