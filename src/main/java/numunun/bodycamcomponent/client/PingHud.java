package numunun.bodycamcomponent.client;

import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import numunun.bodycamcomponent.network.PingClientData;

// 팀원 핑을 화면에 표시 (벽 너머로도 보임)
public final class PingHud {
    private static long lastSeenSeq = 0;

    private PingHud() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        PingClientData.pings.removeIf(p -> now - p.createdAt() > p.lifetimeMs());
        if (PingClientData.pings.isEmpty()) return;

        // 새 핑 알림음
        long maxSeq = lastSeenSeq;
        for (PingClientData.Ping p : PingClientData.pings) maxSeq = Math.max(maxSeq, p.seq());
        if (maxSeq > lastSeenSeq) {
            lastSeenSeq = maxSeq;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f, 0.5f));
        }
        if (mc.options.hideGui) return;

        Font font = mc.font;
        for (PingClientData.Ping p : PingClientData.pings) {
            float[] s = AllyMarkers.projectToScreen(p.x(), p.y(), p.z(), w, h);
            if (s == null) continue;  // 카메라 뒤
            float sx = Mth.clamp(s[0], 12, w - 12);
            float sy = Mth.clamp(s[1], 20, h - 12);

            // 마지막 1초 동안 서서히 사라짐
            long age = now - p.createdAt();
            float alpha = Mth.clamp((p.lifetimeMs() - age) / 1000f, 0f, 1f);
            int a = (int) (alpha * 255) << 24;
            int rgb = p.enemy() ? 0xFF5555 : 0xFFDD55;

            // 마름모
            g.pose().pushPose();
            g.pose().translate(sx, sy, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(45));
            g.fill(-4, -4, 4, 4, a | 0x000000);
            g.fill(-3, -3, 3, 3, a | rgb);
            g.pose().popPose();

            int dist = (int) Math.round(mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z())));
            String label = (p.enemy() ? "ENEMY" : p.sender()) + " " + dist + "m";
            g.drawString(font, label, (int) sx - font.width(label) / 2, (int) sy - 16, a | rgb, true);
        }
    }
}