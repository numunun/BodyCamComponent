package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import numunun.bodycamcomponent.network.OsdClientData;
import numunun.bodycamcomponent.network.OsdPacket;

// 녹화 장비 화면 표시: 왼쪽 위에 PAUSE / PLAY / STOP
public final class OsdOverlay {
    private static final int WHITE = 0xFFFFFFFF;
    private static final long PLAY_HOLD_MS = 1500;
    private static final long STOP_MS = 6000;

    private OsdOverlay() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        OsdPacket d = OsdClientData.current;
        Minecraft mc = Minecraft.getInstance();
        if (d == null || mc.options.hideGui) return;

        long t = System.currentTimeMillis() - OsdClientData.startedAt;
        Font font = mc.font;
        int x = 10, y = 8;

        switch (d.kind()) {
            case OsdPacket.PAUSE -> {
                if ((t / 500) % 2 == 0) {  // 깜빡임
                    g.fill(x, y + 2, x + 4, y + 16, WHITE);
                    g.fill(x + 7, y + 2, x + 11, y + 16, WHITE);
                    bigText(g, font, "PAUSE", x + 18, y);
                }
                subText(g, font, d.sub(), x, y + 24);
            }
            case OsdPacket.PLAY -> {
                long countdown = d.seconds() * 1000L;
                if (t > countdown + PLAY_HOLD_MS) {
                    OsdClientData.current = null;
                    return;
                }
                for (int i = 0; i < 14; i++) {  // 오른쪽을 향한 삼각형
                    int width = 7 - Math.abs(i - 7) + 1;
                    g.fill(x, y + 2 + i, x + width, y + 3 + i, WHITE);
                }
                String text = t < countdown ? "PLAY  " + ((countdown - t + 999) / 1000) : "PLAY";
                bigText(g, font, text, x + 18, y);
            }
            case OsdPacket.STOP -> {
                if (t > STOP_MS) {
                    OsdClientData.current = null;
                    return;
                }
                g.fill(x, y + 3, x + 12, y + 15, WHITE);
                bigText(g, font, "STOP", x + 18, y);
                subText(g, font, d.sub(), x, y + 24);
            }
            default -> { }
        }
    }

    private static void bigText(GuiGraphics g, Font font, String text, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(2f, 2f, 1f);
        g.drawString(font, text, 0, 0, WHITE, true);
        g.pose().popPose();
    }

    private static void subText(GuiGraphics g, Font font, String text, int x, int y) {
        if (!text.isEmpty()) g.drawString(font, text, x, y, 0xFFCCCCCC, true);
    }
}