package numunun.bodycamcomponent.network;

// 클라이언트가 받은 팀 배정 화면 내용
public final class TeamScreenData {
    public static volatile TeamScreenPacket current;
    public static volatile boolean openRequested = false;
    public static volatile int version = 0;

    private TeamScreenData() {}

    static void receive(TeamScreenPacket msg) {
        current = msg;
        version++;
        if (msg.open()) openRequested = true;
    }
}