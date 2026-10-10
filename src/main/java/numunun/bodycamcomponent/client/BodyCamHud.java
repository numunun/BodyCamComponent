package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Locale;
import numunun.bodycamcomponent.network.AbilityClientData;
import numunun.bodycamcomponent.BodyCamComponent;
import org.joml.Matrix4f;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class BodyCamHud {
    // 가상 기기명
    private static final String DEVICE_NAME = "VIGIL BODY 2";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    // 핫바 연출 설정
    private static final long SHOW_MS = 1500;         // 아이템 변경 후 표시 유지 시간
    private static final float FADE_IN_SEC = 0.2f;    // 나타나는 시간
    private static final float FADE_OUT_SEC = 0.6f;   // 사라지는 시간
    private static final float VISIBLE_ALPHA = 0.65f; // 최대 불투명도
    private static final float HIDE_OFFSET = 24f;     // 숨을 때 내려가는 거리

    // 숨길 바닐라 HUD
    private static final Set<ResourceLocation> HIDDEN = Set.of(
            VanillaGuiOverlay.CROSSHAIR.id(),
            VanillaGuiOverlay.PLAYER_HEALTH.id(),
            VanillaGuiOverlay.ARMOR_LEVEL.id(),
            VanillaGuiOverlay.FOOD_LEVEL.id(),
            VanillaGuiOverlay.EXPERIENCE_BAR.id(),
            VanillaGuiOverlay.MOUNT_HEALTH.id(),
            VanillaGuiOverlay.JUMP_BAR.id()
    );

    private static int lastSlot = -1;
    private static ItemStack lastStack = ItemStack.EMPTY;
    private static long lastChange = System.currentTimeMillis();
    private static boolean wasDead = false;
    private static String serial = newSerial();

    private static float visibility = 0f;
    private static long lastFrameNanos = 0L;
    private static TextureTarget hotbarTarget;

    private static String newSerial() {
        return "X" + String.format("%08X", ThreadLocalRandom.current().nextInt());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;

        // 리스폰 감지 → 시리얼 재발급
        if (p.isDeadOrDying()) {
            wasDead = true;
        } else if (wasDead) {
            wasDead = false;
            serial = newSerial();
            lastChange = System.currentTimeMillis();
        }

        // 손에 든 아이템 변경 감지
        int slot = p.getInventory().selected;
        ItemStack held = p.getMainHandItem();
        if (slot != lastSlot || !ItemStack.isSameItem(held, lastStack)) {
            lastSlot = slot;
            lastStack = held.copy();
            lastChange = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre e) {
        ResourceLocation id = e.getOverlay().id();

        if (HIDDEN.contains(id)) {
            e.setCanceled(true);
            return;
        }

        if (id.equals(VanillaGuiOverlay.HOTBAR.id())) {
            e.setCanceled(true);
            float eased = updateVisibility();
            if (eased <= 0.001f) return;
            renderHotbarFaded(e, eased * VISIBLE_ALPHA, (1f - eased) * HIDE_OFFSET);
        }
    }

    private static float updateVisibility() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.1f);

        boolean shouldShow = System.currentTimeMillis() - lastChange < SHOW_MS;
        if (shouldShow) {
            visibility = Math.min(1f, visibility + dt / FADE_IN_SEC);
        } else {
            visibility = Math.max(0f, visibility - dt / FADE_OUT_SEC);
        }
        // smoothstep: 시작과 끝을 부드럽게
        return visibility * visibility * (3f - 2f * visibility);
    }

    private static void renderHotbarFaded(RenderGuiOverlayEvent.Pre e, float alpha, float offset) {
        Minecraft mc = Minecraft.getInstance();
        Window win = e.getWindow();
        GuiGraphics g = e.getGuiGraphics();
        int fbW = win.getWidth();
        int fbH = win.getHeight();
        int sw = win.getGuiScaledWidth();
        int sh = win.getGuiScaledHeight();

        // 1) 별도 화면 준비
        if (hotbarTarget == null) {
            hotbarTarget = new TextureTarget(fbW, fbH, true, Minecraft.ON_OSX);
            hotbarTarget.setClearColor(0f, 0f, 0f, 0f);
        } else if (hotbarTarget.width != fbW || hotbarTarget.height != fbH) {
            hotbarTarget.resize(fbW, fbH, Minecraft.ON_OSX);
        }

        // 2) 바닐라 핫바를 별도 화면에 그리기
        g.flush();
        hotbarTarget.clear(Minecraft.ON_OSX);
        hotbarTarget.bindWrite(true);

        g.pose().pushPose();
        g.pose().translate(0f, offset, 0f);
        VanillaGuiOverlay.HOTBAR.type().overlay().render((ForgeGui) mc.gui, g, e.getPartialTick(), sw, sh);
        g.flush();
        g.pose().popPose();

        mc.getMainRenderTarget().bindWrite(true);

        // 3) 결과를 반투명하게 덮어씌우기
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, hotbarTarget.getColorTextureId());
        RenderSystem.setShaderColor(alpha, alpha, alpha, alpha);

        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(m, 0, sh, 0).uv(0, 0).endVertex();
        bb.vertex(m, sw, sh, 0).uv(1, 0).endVertex();
        bb.vertex(m, sw, 0, 0).uv(1, 1).endVertex();
        bb.vertex(m, 0, 0, 0).uv(0, 1).endVertex();
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
    }

    public static void renderInfo(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;

        Font font = mc.font;
        String line1 = ZonedDateTime.now().format(FMT);
        String line2 = DEVICE_NAME + " " + serial;

        g.drawString(font, line1, w - font.width(line1) - 6, 6, 0xFFFFFF, true);
        g.drawString(font, line2, w - font.width(line2) - 6, 16, 0xFFFFFF, true);
        // 경기 중: 직급 · 팀 (팀 이름은 팀 색)
        String role = AbilityClientData.role;
        if (!role.isEmpty()) {
            String roleText = role.toUpperCase(Locale.ROOT);
            String team = AbilityClientData.team.toUpperCase(Locale.ROOT);
            String head = team.isEmpty() ? roleText : roleText + " · ";
            int x = w - font.width(head + team) - 6;
            g.drawString(font, head, x, 26, 0xFFFFFF, true);
            if (!team.isEmpty()) {
                g.drawString(font, team, x + font.width(head), 26, 0xFF000000 | AbilityClientData.teamColor, true);
            }
        }
    }
}