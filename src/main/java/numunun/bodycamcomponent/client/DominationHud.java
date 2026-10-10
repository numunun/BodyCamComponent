package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import numunun.bodycamcomponent.network.DominationClientData;
import numunun.bodycamcomponent.network.DominationSyncPacket;

import java.util.HashMap;
import java.util.Map;

// 점령전 줄다리기 바: 왼쪽 팀 색 ↔ 오른쪽 팀 색
public final class DominationHud {
    private static final int BAR_W = 100;
    private static final int BAR_H = 5;
    private static final int ROW_H = 12;
    private static final float SMOOTH = 8f;  // 클수록 새 값을 빨리 따라감

    // 거점별로 화면에 그리고 있는 값 (서버 값을 향해 부드럽게 따라감)
    private static final Map<String, Float> shown = new HashMap<>();
    private static long lastNanos = 0L;

    private DominationHud() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        DominationSyncPacket d = DominationClientData.current;
        if (d.points().isEmpty()) {
            shown.clear();  // 경기가 끝나면 다음 경기는 처음부터
            return;
        }

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min((now - lastNanos) / 1_000_000_000f, 0.1f);
        lastNanos = now;
        float k = Math.min(1f, dt * SMOOTH);

        if (mc.options.hideGui) return;

        Font font = mc.font;
        int leftColor = 0xFF000000 | teamRgb(mc, d.leftTeam(), 0x5555FF);
        int rightColor = 0xFF000000 | teamRgb(mc, d.rightTeam(), 0xFF5555);
        boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;

        int x = w / 2 - BAR_W / 2;
        int y = 8;
        for (DominationSyncPacket.Point p : d.points()) {
            Float cur = shown.get(p.name());
            float value = cur == null ? p.leftShare() : cur + (p.leftShare() - cur) * k;
            shown.put(p.name(), value);
            int split = x + Math.round(BAR_W * value);

            g.drawString(font, p.name(), x - 6 - font.width(p.name()), y - 1, 0xFFFFFFFF, true);
            int border = p.contested() && blink ? 0xFFFFFF55 : 0xAA000000;
            g.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, border);
            g.fill(x, y, split, y + BAR_H, leftColor);
            g.fill(split, y, x + BAR_W, y + BAR_H, rightColor);
            g.fill(x + BAR_W / 2, y - 2, x + BAR_W / 2 + 1, y + BAR_H + 2, 0xAAFFFFFF);  // 가운데 기준선
            if (p.contested()) g.drawString(font, "대치", x + BAR_W + 6, y - 1, 0xFFFFFF55, true);

            y += ROW_H;
        }
    }

    private static int teamRgb(Minecraft mc, String team, int fallback) {
        if (mc.level == null || team.isEmpty()) return fallback;
        PlayerTeam t = mc.level.getScoreboard().getPlayerTeam(team);
        Integer c = t != null ? t.getColor().getColor() : null;
        return c != null ? c : fallback;
    }
}