package numunun.bodycamcomponent.network;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// 클라이언트가 받은 핑 보관 (한 사람당 하나)
public final class PingClientData {
    public record Ping(String sender, double x, double y, double z, boolean enemy,
                       long createdAt, long lifetimeMs, long seq) {}

    public static final List<Ping> pings = new CopyOnWriteArrayList<>();
    private static long seq = 0;

    private PingClientData() {}

    static void add(PingPacket p) {
        pings.removeIf(old -> old.sender().equals(p.sender()));
        pings.add(new Ping(p.sender(), p.x(), p.y(), p.z(), p.enemy(),
                System.currentTimeMillis(), p.lifetimeMs(), ++seq));
    }
}