package numunun.bodycamcomponent.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// 모드 전용 로그. 게임 로그 파일(logs/latest.log)에 [BodyCam]으로 기록
public final class BodyCamLog {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String PREFIX = "[BodyCam] ";
    private static final long REPEAT_MS = 10_000;  // 같은 오류는 10초에 한 번만

    private static final Map<String, Long> lastError = new ConcurrentHashMap<>();
    private static final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private BodyCamLog() {}

    public static void info(String msg) {
        LOG.info(PREFIX + msg);
    }

    public static void warn(String msg) {
        LOG.warn(PREFIX + msg);
    }

    // 설정 실수 같은 경고는 한 번만 (매 리스폰마다 같은 경고가 쌓이지 않게)
    public static void warnOnce(String key, String msg) {
        if (warnedOnce.add(key)) LOG.warn(PREFIX + msg);
    }

    // 매 틱 반복되는 오류로 로그가 넘치지 않게, 같은 위치의 오류는 10초에 한 번만 기록
    public static void error(String where, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = lastError.get(where);
        if (last != null && now - last < REPEAT_MS) return;
        lastError.put(where, now);
        LOG.error(PREFIX + "오류: " + where, t);
    }
}