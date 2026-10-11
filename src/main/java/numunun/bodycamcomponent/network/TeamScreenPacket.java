package numunun.bodycamcomponent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// 서버 → 클라이언트: 팀 배정 화면 내용 (open이면 화면을 새로 띄움)
public record TeamScreenPacket(String leftName, int leftColor, String rightName, int rightColor,
                               boolean canEdit, boolean open, List<Entry> entries) {

    // side: 0 왼쪽 팀, 1 오른쪽 팀, 2 제외
    public record Entry(UUID id, String name, int rating, int side) {}

    public static void encode(TeamScreenPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.leftName);
        buf.writeInt(msg.leftColor);
        buf.writeUtf(msg.rightName);
        buf.writeInt(msg.rightColor);
        buf.writeBoolean(msg.canEdit);
        buf.writeBoolean(msg.open);
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeUUID(e.id());
            buf.writeUtf(e.name());
            buf.writeVarInt(e.rating());
            buf.writeVarInt(e.side());
        }
    }

    public static TeamScreenPacket decode(FriendlyByteBuf buf) {
        String leftName = buf.readUtf();
        int leftColor = buf.readInt();
        String rightName = buf.readUtf();
        int rightColor = buf.readInt();
        boolean canEdit = buf.readBoolean();
        boolean open = buf.readBoolean();
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
        }
        return new TeamScreenPacket(leftName, leftColor, rightName, rightColor, canEdit, open, list);
    }

    public static void handle(TeamScreenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> TeamScreenData.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}