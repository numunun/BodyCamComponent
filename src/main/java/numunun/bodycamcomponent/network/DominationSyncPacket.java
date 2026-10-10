package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// 서버 → 클라이언트: 점령전 거점별 줄다리기 상태
public record DominationSyncPacket(String leftTeam, String rightTeam, List<Point> points) {
    public record Point(String name, float leftShare, boolean contested) {}

    public static final DominationSyncPacket EMPTY = new DominationSyncPacket("", "", List.of());

    public static void encode(DominationSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.leftTeam);
        buf.writeUtf(msg.rightTeam);
        buf.writeVarInt(msg.points.size());
        for (Point p : msg.points) {
            buf.writeUtf(p.name());
            buf.writeFloat(p.leftShare());
            buf.writeBoolean(p.contested());
        }
    }

    public static DominationSyncPacket decode(FriendlyByteBuf buf) {
        String left = buf.readUtf();
        String right = buf.readUtf();
        int n = buf.readVarInt();
        List<Point> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(new Point(buf.readUtf(), buf.readFloat(), buf.readBoolean()));
        return new DominationSyncPacket(left, right, list);
    }

    public static void handle(DominationSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DominationClientData.current = msg);
        ctx.get().setPacketHandled(true);
    }
}