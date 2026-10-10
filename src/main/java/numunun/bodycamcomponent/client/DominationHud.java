package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import numunun.bodycamcomponent.network.DominationClientData;
import numunun.bodycamcomponent.network.DominationSyncPacket;

// 점령전 줄다리기 바: 왼쪽 팀 색 ↔ 오른쪽 팀 색
public final class DominationHud {
    private static final int BAR_W = 100;
    private static final int BAR_H = 5;
    private static final int ROW_H = 12;

    private DominationHud() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        DominationSyncPacket d = DominationClientData.current;
        if (mc.options.hideGui || d.points().isEmpty()) return;

        Font font = mc.font;
        int leftColor = 0xFF000000 | teamRgb(mc, d.leftTeam(), 0x5555FF);
        int rightColor = 0xFF000000 | teamRgb(mc, d.rightTeam(), 0xFF5555);
        boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;

        int x = w / 2 - BAR_W / 2;
        int y = 8;
        for (DominationSyncPacket.Point p : d.points()) {
            int split = x + Math.round(BAR_W * p.leftShare());

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