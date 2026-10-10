package numunun.bodycamcomponent.network;

public final class IntroClientData {
    public static volatile IntroPacket current;
    public static volatile long startedAt;

    private IntroClientData() {}

    static void start(IntroPacket msg) {
        current = msg;
        startedAt = System.currentTimeMillis();
    }
}