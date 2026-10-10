package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import numunun.bodycamcomponent.network.IntroClientData;
import numunun.bodycamcomponent.network.IntroPacket;

import java.util.ArrayList;
import java.util.List;

// 경기 시작 연출: 암전 → 밝아지며 왼쪽 중하단에 소속/팀/직급/이름을 직접 타이핑
public final class IntroOverlay {
    private static final long FADE_MS = 1500;       // 밝아지는 시간
    private static final long TEXT_LEAD_MS = 3000;  // 암전이 끝나기 이만큼 전부터 타이핑 시작
    private static final long STROKE_MS = 55;       // 한 타 기본 간격
    private static final long LINE_PAUSE_MS = 300;  // 줄 사이 쉬는 시간
    private static final long HOLD_MS = 3500;       // 다 친 뒤 유지
    private static final long OUT_MS = 1000;        // 글자 사라지는 시간
    private static final boolean TYPE_SOUND = false; // 키보드 소리
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

    // 연출이 새로 들어올 때 한 번 계산해 두는 타이핑 일정
    private static IntroPacket cached;
    private static List<List<String>> lineStates;  // 줄마다, 한 타 칠 때마다의 화면 문자열
    private static List<long[]> lineTimes;         // 줄마다, 각 타가 끝나는 시각
    private static long[] lineStarts;
    private static long typingEnd;
    private static int lastStrokes;

    private IntroOverlay() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);  // 채팅 등 다른 HUD보다 앞에 그리기
        draw(g, w, h);
        g.pose().popPose();
    }

    private static void draw(GuiGraphics g, int w, int h) {
        IntroPacket intro = IntroClientData.current;
        if (intro == null) return;
        if (intro != cached) prepare(intro);

        long t = System.currentTimeMillis() - IntroClientData.startedAt;
        long blackMs = intro.blackTicks() * 50L;
        long textStart = Math.max(0, blackMs - TEXT_LEAD_MS);       // 암전 중에 미리 시작
        long textEnd = blackMs + typingEnd + HOLD_MS + OUT_MS;      // 사라지는 시점은 그대로
        if (t > Math.max(blackMs + FADE_MS, textEnd)) {
            IntroClientData.current = null;
            cached = null;
            return;
        }

        // 암전, 이후 서서히 밝아짐
        float black = t < blackMs ? 1f : 1f - Mth.clamp((t - blackMs) / (float) FADE_MS, 0f, 1f);
        if (black > 0f) g.fill(0, 0, w, h, (int) (black * 255) << 24);
        if (t < textStart) return;

        long tt = t - textStart;
        float textAlpha = t < textEnd - OUT_MS ? 1f
                : 1f - Mth.clamp((t - (textEnd - OUT_MS)) / (float) OUT_MS, 0f, 1f);
        int a = Math.max(4, (int) (textAlpha * 255)) << 24;

        Font font = Minecraft.getInstance().font;
        int x = 24;
        int y = (int) (h * 0.62f);
        int[] ys = {y, y + 12, y + 24, y + 42};
        int[] colors = {0x999999, intro.teamColor(), 0xFFFFFF, 0xAAAAAA};
        boolean blinkOn = (System.currentTimeMillis() / 400) % 2 == 0;

        // 지금 치고 있는(가장 최근에 시작한) 줄
        int active = 0;
        for (int l = 0; l < lineStarts.length; l++) {
            if (tt >= lineStarts[l]) active = l;
        }

        int totalStrokes = 0;
        for (int l = 0; l < lineStarts.length; l++) {
            long local = tt - lineStarts[l];
            if (local < 0) break;

            long[] times = lineTimes.get(l);
            int k = 0;
            while (k < times.length && times[k] <= local) k++;
            totalStrokes += k;

            String text = k == 0 ? "" : lineStates.get(l).get(k - 1);
            if (l == active) {
                boolean typing = k < times.length;
                if (typing || blinkOn) text += "_";  // 치는 중엔 켜져 있고, 쉴 땐 깜빡임
            }

            int color = a | colors[l];
            if (l == 2) {  // 직급은 크게
                g.pose().pushPose();
                g.pose().translate(x, ys[l], 0);
                g.pose().scale(1.6f, 1.6f, 1f);
                g.drawString(font, text, 0, 0, color, true);
                g.pose().popPose();
            } else {
                g.drawString(font, text, x, ys[l], color, true);
            }
        }

        // 새로 친 타가 있으면 키보드 소리
        if (TYPE_SOUND && totalStrokes > lastStrokes) {
            lastStrokes = totalStrokes;
            float pitch = 1.7f + (totalStrokes % 5) * 0.08f;
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.12f));
        }
    }

    // ── 타이핑 일정 계산 ──
    private static void prepare(IntroPacket intro) {
        String[] lines = {intro.unitLine(), intro.teamLine(), intro.role(), intro.name()};
        lineStates = new ArrayList<>();
        lineTimes = new ArrayList<>();
        lineStarts = new long[lines.length];

        long cursor = 0;
        for (int l = 0; l < lines.length; l++) {
            List<String> states = strokes(lines[l]);
            long[] times = new long[states.size()];
            long acc = 0;
            for (int i = 0; i < states.size(); i++) {
                acc += STROKE_MS + Math.floorMod(i * 7919 + l * 104729, 35);  // 사람처럼 들쭉날쭉
                if (states.get(i).endsWith(" ")) acc += 40;                     // 띄어쓰기에서 살짝 멈칫
                times[i] = acc;
            }
            lineStarts[l] = cursor;
            lineStates.add(states);
            lineTimes.add(times);
            cursor += acc + LINE_PAUSE_MS;
        }
        typingEnd = cursor;
        lastStrokes = 0;
        cached = intro;
    }

    // 한 타 칠 때마다 화면에 보이는 문자열 (한글은 자모 단위로 조합)
    private static List<String> strokes(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            String prefix = text.substring(0, i);
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int idx = c - 0xAC00;
                int cho = idx / (21 * 28);
                int jung = (idx % (21 * 28)) / 28;
                int jong = idx % 28;
                out.add(prefix + CHO.charAt(cho));                         // ㄷ
                out.add(prefix + (char) (0xAC00 + (cho * 21 + jung) * 28)); // 드
                if (jong != 0) out.add(prefix + c);                          // 받침까지
            } else {
                out.add(prefix + c);
            }
        }
        return out;
    }
}