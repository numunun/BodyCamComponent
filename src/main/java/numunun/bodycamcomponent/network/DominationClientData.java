package numunun.bodycamcomponent.network;

// 클라이언트가 받은 점령전 상태 보관
public final class DominationClientData {
    public static volatile DominationSyncPacket current = DominationSyncPacket.EMPTY;

    private DominationClientData() {}
}