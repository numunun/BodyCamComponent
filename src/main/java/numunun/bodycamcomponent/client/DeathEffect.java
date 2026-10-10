package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;

import java.util.Random;

// 사망 시 바디캠 신호 끊김 연출: 노이즈 → 암전 → 서서히 밝아짐
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class DeathEffect {
    private static final long NOISE_MS = 400;
    private static final long BLACK_MS = 900;
    private static final long FADE_MS = 800;
    private static final ResourceLocation NOISE_ID = new ResourceLocation(BodyCamComponent.MODID, "dynamic/noise");
    private static final Random RANDOM = new Random();

    private static boolean noiseReady = false;
    private static long startMs = -1;
    private static boolean wasDead = false;

    private static void trigger() {
        if (startMs >= 0 && System.currentTimeMillis() - startMs < 2000) return;  // 중복 방지
        startMs = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (p.isDeadOrDying()) {
            if (!wasDead) trigger();
            wasDead = true;
        } else {
            wasDead = false;
        }
    }

    // 즉시 리스폰이라 사망 상태를 못 볼 수 있어서, 리스폰 순간에도 확인
    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone e) {
        if (e.getOldPlayer().isDeadOrDying()) trigger();
    }

    private static void ensureNoise() {
        if (noiseReady) return;
        NativeImage img = new NativeImage(256, 256, false);
        for (int x = 0; x < 256; x++) {
            for (int y = 0; y < 256; y++) {
                int v = RANDOM.nextInt(256);
                img.setPixelRGBA(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
        Minecraft.getInstance().getTextureManager().register(NOISE_ID, new DynamicTexture(img));
        noiseReady = true;
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);  // 채팅 등 다른 HUD보다 앞에 그리기
        draw(g, w, h);
        g.pose().popPose();
    }

    private static void draw(GuiGraphics g, int w, int h) {
        if (startMs < 0) return;
        long t = System.currentTimeMillis() - startMs;
        if (t > NOISE_MS + BLACK_MS + FADE_MS) {
            startMs = -1;
            return;
        }

        if (t < NOISE_MS) {
            // 지지직 노이즈 (매 프레임 다른 부분을 확대해서 보여줌)
            ensureNoise();
            int vh = Math.max(1, 128 * h / Math.max(1, w));
            g.blit(NOISE_ID, 0, 0, w, h, RANDOM.nextInt(128), RANDOM.nextInt(128), 128, vh, 256, 256);
            return;
        }

        float alpha = t < NOISE_MS + BLACK_MS ? 1f : 1f - (t - NOISE_MS - BLACK_MS) / (float) FADE_MS;
        g.fill(0, 0, w, h, ((int) (alpha * 255) << 24));

        // 암전 중 "SIGNAL LOST" 깜빡임
        if (t < NOISE_MS + BLACK_MS && (t / 250) % 2 == 0) {
            Font font = Minecraft.getInstance().font;
            String text = "SIGNAL LOST";
            g.drawString(font, text, w / 2 - font.width(text) / 2, h / 2 - 4, 0xFFBBBBBB, false);
        }
    }
}