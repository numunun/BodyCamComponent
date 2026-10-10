package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.network.AbilityClientData;
import numunun.bodycamcomponent.downed.DownedManager;
import numunun.bodycamcomponent.util.Safe;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class AllyMarkers {
    private static final double MAX_DIST = 128.0;
    private static final int COLOR = 0xFF55FF55;  // 초록
    private static final double RADIUS_STANDING = 0.45;  // 서 있을 때 몸 반경
    private static final double RADIUS_LYING = 0.9;

    private static final Set<UUID> visible = new HashSet<>();
    private static final Matrix4f view = new Matrix4f();
    private static final Matrix4f proj = new Matrix4f();
    private static Vec3 camPos = Vec3.ZERO;
    private static boolean hasMatrices = false;

    // ── 이번 프레임의 카메라 정보 저장 (화면 좌표 계산용) ──
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        view.set(e.getPoseStack().last().pose());
        proj.set(e.getProjectionMatrix());
        camPos = e.getCamera().getPosition();
        hasMatrices = true;
    }

    // ── 시야에 보이는 아군 갱신 (매 틱) ──
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Safe.run("아군 시야 판정", AllyMarkers::updateVisible);
    }

    private static void updateVisible() {
        visible.clear();

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        ClientLevel level = mc.level;
        if (me == null || level == null) return;
        Team myTeam = me.getTeam();
        if (myTeam == null) return;

        Vec3 eye = me.getEyePosition();
        for (AbstractClientPlayer other : level.players()) {
            if (other == me || other.isSpectator() || other.isInvisible()) continue;
            Team t = other.getTeam();
            if (t == null || !t.getName().equals(myTeam.getName())) continue;
            if (other.distanceToSqr(me) > MAX_DIST * MAX_DIST) continue;
            if (AbilityClientData.seeAlliesThroughWalls || canSee(level, me, eye, other)) visible.add(other.getUUID());
        }
    }

    private static boolean canSee(ClientLevel level, LocalPlayer me, Vec3 eye, Player other) {
        Vec3[] points = {
                other.getEyePosition(),
                other.position().add(0, other.getBbHeight() * 0.5, 0),
                other.position().add(0, 0.2, 0)
        };
        for (Vec3 p : points) {
            HitResult hit = level.clip(new ClipContext(eye, p,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, me));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    // ── HUD에 사각형과 이름 그리기 ──
    // ── HUD에 사각형과 이름 그리기 ──
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !hasMatrices || mc.level == null || visible.isEmpty()) return;
        Font font = mc.font;

        for (UUID id : visible) {
            Player other = mc.level.getPlayerByUUID(id);
            if (other == null) continue;

            Vec3 pos = other.getPosition(partialTick);
            double height = other.getBbHeight();
            Pose pose = other.getPose();
            boolean lying = pose == Pose.SWIMMING || pose == Pose.FALL_FLYING || pose == Pose.SLEEPING;
            double r = lying ? RADIUS_LYING : RADIUS_STANDING;

            // 내 쪽 방향(toward)과 그 옆 방향(side), 수평 기준
            double dx = camPos.x - pos.x;
            double dz = camPos.z - pos.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-4) { dx = 1; dz = 0; len = 1; }
            double tx = dx / len * r, tz = dz / len * r;
            double sx = -tz, sz = tx;

            float[] rect = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            boolean onScreen = true;
            for (double y : new double[]{pos.y, pos.y + height}) {
                onScreen &= extend(rect, pos.x + sx, y, pos.z + sz, w, h);
                onScreen &= extend(rect, pos.x - sx, y, pos.z - sz, w, h);
                onScreen &= extend(rect, pos.x + tx, y, pos.z + tz, w, h);
                onScreen &= extend(rect, pos.x - tx, y, pos.z - tz, w, h);
            }
            if (!onScreen) continue;

            int x1 = (int) rect[0], y1 = (int) rect[1], x2 = (int) rect[2], y2 = (int) rect[3];
            boolean downed = DownedManager.isDowned(other);
            int color = downed ? 0xFFFF5555 : COLOR;  // 쓰러진 팀원은 빨간색
            g.fill(x1, y1, x2, y1 + 1, color);
            g.fill(x1, y2 - 1, x2, y2, color);
            g.fill(x1, y1, x1 + 1, y2, color);
            g.fill(x2 - 1, y1, x2, y2, color);

            String name = downed ? other.getScoreboardName() + " | DOWNED" : other.getScoreboardName();
            g.drawString(font, name, (x1 + x2) / 2 - font.width(name) / 2, y1 - 10, color, true);
        }
    }

    // 월드 좌표 한 점을 화면에 투영해서 사각형 범위에 포함
    private static boolean extend(float[] rect, double x, double y, double z, int w, int h) {
        Vector4f v = new Vector4f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z), 1f);
        v.mul(view);
        v.mul(proj);
        if (v.w() <= 0.01f) return false;  // 카메라 뒤쪽

        float[] d = distort(v.x() / v.w(), v.y() / v.w(), w, h);
        float px = (d[0] * 0.5f + 0.5f) * w;
        float py = (1f - (d[1] * 0.5f + 0.5f)) * h;
        rect[0] = Math.min(rect[0], px);
        rect[1] = Math.min(rect[1], py);
        rect[2] = Math.max(rect[2], px);
        rect[3] = Math.max(rect[3], py);
        return true;
    }

    // 쉐이더 렌즈 왜곡을 맞추는 자리 (공식을 받으면 채울 예정, 지금은 그대로 통과)
    // 쉐이더 렌즈 왜곡을 거꾸로 적용: 원래 화면 위치 → 최종 화면 위치
    private static float[] distort(float nx, float ny, int w, int h) {
        ShaderLensSettings.refresh();
        if (!ShaderCompat.isShaderActive() || !ShaderLensSettings.isBodycam()) return new float[]{nx, ny};

        float u = nx * 0.5f + 0.5f;
        float v = ny * 0.5f + 0.5f;

        // 4:3 비율 강제
        float visibleWidth = 1f, margin = 0f;
        float aspect = (float) w / h, target = 4f / 3f;
        if (ShaderLensSettings.force43() && aspect > target) {
            visibleWidth = target / aspect;
            margin = (1f - visibleWidth) / 2f;
        }

        // 효과 공간에서 중심 기준 좌표
        float qx = (u - margin) / visibleWidth - 0.5f;
        float qy = v - 0.5f;
        float R = (float) Math.sqrt(qx * qx + qy * qy);

        // 어안 왜곡 역산: K*r^3 + r = R 을 뉴턴법으로 풀기
        float scale = 1f;
        if (R > 1e-6f) {
            float r = R;
            for (int i = 0; i < 8; i++) {
                float f = ShaderLensSettings.distStrength() * r * r * r + r - R;
                float df = 3f * ShaderLensSettings.distStrength() * r * r + 1f;
                r = Math.max(0f, r - f / df);
            }
            scale = r / R;
        }

        // 확대 역산 후 화면 좌표로 복귀
        float ex = qx * scale / ShaderLensSettings.zoom() + 0.5f;
        float ey = qy * scale / ShaderLensSettings.zoom() + 0.5f;
        float tu = ex * visibleWidth + margin;
        return new float[]{tu * 2f - 1f, ey * 2f - 1f};
    }

    // 월드 좌표 → 화면 좌표 (카메라 뒤면 null). 핑 표시에서도 사용
    static float[] projectToScreen(double x, double y, double z, int w, int h) {
        if (!hasMatrices) return null;
        Vector4f v = new Vector4f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z), 1f);
        v.mul(view);
        v.mul(proj);
        if (v.w() <= 0.01f) return null;
        float[] d = distort(v.x() / v.w(), v.y() / v.w(), w, h);
        return new float[]{(d[0] * 0.5f + 0.5f) * w, (1f - (d[1] * 0.5f + 0.5f)) * h};
    }
}