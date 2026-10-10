package numunun.bodycamcomponent.util;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// 경기 기록 파일: logs/bodycam/날짜_작전명.log 에 경기 중 일어난 일을 시간순으로 기록
public final class MatchLog {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static BufferedWriter out;

    private MatchLog() {}

    public static void begin(String codename) {
        end();
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("bodycam");
            Files.createDirectories(dir);
            String safeName = codename.replaceAll("[\\\\/:*?\"<>|]", "_").replace(' ', '_');
            Path file = dir.resolve(LocalDateTime.now().format(FILE_TIME) + "_" + safeName + ".log");
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            out = null;
            BodyCamLog.error("경기 기록 파일 만들기", e);
        }
    }

    // 경기 중일 때만 기록 (게임 로그에도 같이 남김)
    public static void event(String line) {
        if (out == null) return;
        BodyCamLog.info(line);
        try {
            out.write("[" + LocalTime.now().format(LINE_TIME) + "] " + line);
            out.newLine();
            out.flush();  // 서버가 갑자기 꺼져도 기록이 남게
        } catch (IOException e) {
            BodyCamLog.error("경기 기록 쓰기", e);
            end();
        }
    }

    public static boolean isOpen() {
        return out != null;
    }

    public static void end() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }
}