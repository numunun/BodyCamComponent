package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.downed.DownedManager;
import numunun.bodycamcomponent.util.BodyCamLog;
import org.joml.Matrix4f;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class LowHealthEffect {
    private static final float START = 0.35f;          // 이 체력 비율부터 색이 빠지기 시작
    private static final float END = 0.10f;            // 이 체력 비율에서 최대 회색조
    private static final float MIN_SATURATION = 0.05f; // 최대 회색조일 때 남는 색
    private static final float LERP_SPEED = 2.0f;      // 변화 속도 (클수록 빠름)

    static ShaderInstance shader;
    private static TextureTarget workTarget;
    private static float saturation = 1f;
    private static long lastNanos = 0L;
    private static boolean wasDead = false;
    private static boolean broken = false;  // 쉐이더 오류가 나면 이번 실행 동안 효과를 끔

    private static float targetSaturation(LocalPlayer p) {
        if (p.isCreative() || p.isSpectator()) return 1f;
        if (DownedManager.isDowned(p)) return MIN_SATURATION;   // 추가: 빈사 상태면 완전 흑백
        float ratio = p.getHealth() / p.getMaxHealth();
        float t = Mth.clamp((ratio - END) / (START - END), 0f, 1f);
        return Mth.lerp(t, MIN_SATURATION, 1f);
    }

    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre e) {
        LocalPlayer p = Minecraft.getInstance().player;

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min((now - lastNanos) / 1_000_000_000f, 0.1f);
        lastNanos = now;

        // 리스폰 직후에는 색을 즉시 복구
        if (p != null) {
            if (p.isDeadOrDying()) {
                wasDead = true;
            } else if (wasDead) {
                wasDead = false;
                saturation = 1f;
            }
        }

        float target = p == null ? 1f : targetSaturation(p);
        saturation += (target - saturation) * Math.min(1f, dt * LERP_SPEED);

        if (saturation > 0.995f || shader == null || broken) return;
        try {
            apply(e.getGuiGraphics(), e.getWindow());
        } catch (Exception ex) {
            broken = true;  // 매 프레임 오류가 나지 않게 효과를 끔
            BodyCamLog.error("저체력 흑백 효과 (이번 실행 동안 꺼짐)", ex);
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);  // 화면 출력 대상 원상 복구
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
        }
    }

    private static void apply(GuiGraphics g, Window win) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        int sw = win.getGuiScaledWidth();
        int sh = win.getGuiScaledHeight();

        if (workTarget == null) {
            workTarget = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        } else if (workTarget.width != w || workTarget.height != h) {
            workTarget.resize(w, h, Minecraft.ON_OSX);
        }

        g.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();

        // 1) 원본 화면 → 회색조 쉐이더 → 작업용 화면
        workTarget.bindWrite(true);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, main.getColorTextureId());
        shader.safeGetUniform("Saturation").set(saturation);
        drawFullscreenQuad(g, sw, sh);

        // 2) 작업용 화면 → 원본 화면에 덮어쓰기
        main.bindWrite(true);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, workTarget.getColorTextureId());
        drawFullscreenQuad(g, sw, sh);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    private static void drawFullscreenQuad(GuiGraphics g, int sw, int sh) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(m, 0, sh, 0).uv(0, 0).endVertex();
        bb.vertex(m, sw, sh, 0).uv(1, 0).endVertex();
        bb.vertex(m, sw, 0, 0).uv(1, 1).endVertex();
        bb.vertex(m, 0, 0, 0).uv(0, 1).endVertex();
        BufferUploader.drawWithShader(bb.end());
    }
}