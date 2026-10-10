package numunun.bodycamcomponent.util;

// 오류가 나도 게임(서버, 클라이언트)이 멈추지 않게 감싸서 실행하고 로그만 남김
public final class Safe {
    private Safe() {}

    public static void run(String where, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            BodyCamLog.error(where, e);
        }
    }
}