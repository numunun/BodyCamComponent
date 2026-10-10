package numunun.bodycamcomponent.network;

public final class OsdClientData {
    public static volatile OsdPacket current;
    public static volatile long startedAt;

    private OsdClientData() {}

    static void show(OsdPacket msg) {
        current = msg.kind() == OsdPacket.HIDE ? null : msg;
        startedAt = System.currentTimeMillis();
    }
}