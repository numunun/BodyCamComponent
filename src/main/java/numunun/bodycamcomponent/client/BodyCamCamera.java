package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class BodyCamCamera {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double DOWN = 0.30;     // 눈높이에서 내릴 거리
    private static final double FORWARD = 0.20;  // 몸 앞쪽으로 뺄 거리
    private static final float HAND_RAISE = 0.12f;  // 손/총을 올릴 높이
    private static final float HAND_TILT = -6f;  // 손/총 기울기 (도), 팔 쪽이 올라감
    private static final float HAND_X = -0.10f;   // 좌우 이동 (음수 = 왼쪽, 화면 중앙 쪽)
    private static final float HAND_YAW = 5f;     // 총구 방향 회전 (도)
    private static final boolean CAMERA_TO_EYE_WHEN_AIMING = true;  // 정조준 시 카메라를 눈 위치로
    private static final float CAMERA_AIM_SEC = 0.3f;  // 정조준 시 카메라 이동 시간

    private static float cameraAimLinear = 0f;
    private static long lastCamNanos = 0L;

    private static float smoothstep(float x) {
        return x * x * (3f - 2f * x);
    }
    private static final boolean ALLOW_THIRD_PERSON_IN_CREATIVE = false;

    private static Field positionField;
    private static boolean reflectionFailed = false;
    private static PoseStack.Pose lastRaisedPose;
    private static Boolean taczLoaded;
    private static boolean taczFailed = false;

    // ── TaCZ 정조준 진행도 (0 = 평소, 1 = 완전 정조준) ──
    private static float aimProgress(LocalPlayer p, float partialTick) {
        if (taczLoaded == null) taczLoaded = ModList.get().isLoaded("tacz");
        if (!taczLoaded || taczFailed) return 0f;
        try {
            return Mth.clamp(TaczCompat.aimingProgress(p, partialTick), 0f, 1f);
        } catch (Throwable t) {
            taczFailed = true;
            LOGGER.warn("[BodyCam] TaCZ aiming API unavailable; aim compensation disabled", t);
            return 0f;
        }
    }

    // ── 가슴 카메라 ──
    @SubscribeEvent
    public static void onComputeCamera(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson()) return;
        if (!(mc.getCameraEntity() instanceof LocalPlayer p) || p != mc.player) return;

        float pt = (float) e.getPartialTick();

        // 카메라는 정조준 진행도를 자체 속도로 따라감
        long now = System.nanoTime();
        float dt = lastCamNanos == 0L ? 0f : Math.min((now - lastCamNanos) / 1_000_000_000f, 0.1f);
        lastCamNanos = now;

        float aimTarget = CAMERA_TO_EYE_WHEN_AIMING ? aimProgress(p, pt) : 0f;
        float step = dt / CAMERA_AIM_SEC;
        if (cameraAimLinear < aimTarget) {
            cameraAimLinear = Math.min(aimTarget, cameraAimLinear + step);
        } else {
            cameraAimLinear = Math.max(aimTarget, cameraAimLinear - step);
        }

        Pose pose = p.getPose();
        if (pose != Pose.STANDING && pose != Pose.CROUCHING) return;

        float scale = 1f - smoothstep(cameraAimLinear);
        if (scale <= 0.001f) return;

        float bodyYaw = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
        double rad = Math.toRadians(bodyYaw);
        double dx = -Math.sin(rad) * FORWARD * scale;
        double dz = Math.cos(rad) * FORWARD * scale;

        Camera cam = e.getCamera();
        Vec3 pos = cam.getPosition();
        setCameraPosition(cam, pos.x + dx, pos.y - DOWN * scale, pos.z + dz);
    }

    private static void setCameraPosition(Camera cam, double x, double y, double z) {
        if (reflectionFailed) return;
        try {
            if (positionField == null) {
                for (Field f : Camera.class.getDeclaredFields()) {
                    if (f.getType() == Vec3.class && !Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        positionField = f;
                        break;
                    }
                }
                if (positionField == null) {
                    reflectionFailed = true;
                    LOGGER.warn("[BodyCam] Camera position field not found; chest camera disabled");
                    return;
                }
            }
            positionField.set(cam, new Vec3(x, y, z));
            if (cam.getBlockPosition() instanceof BlockPos.MutableBlockPos mutable) {
                mutable.set(x, y, z);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reflectionFailed = true;
            LOGGER.warn("[BodyCam] Failed to move camera; chest camera disabled", ex);
        }
    }

    // ── 손/총 위치 올리기 ──
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderHand(RenderHandEvent e) {
        // 주손/보조손이 같은 프레임에 두 번 호출되므로 한 번만 적용
        PoseStack.Pose top = e.getPoseStack().last();
        if (top == lastRaisedPose) return;
        lastRaisedPose = top;

        LocalPlayer p = Minecraft.getInstance().player;
        float factor = 1f;
        if (p != null) factor = 1f - smoothstep(aimProgress(p, e.getPartialTick()));
        if (factor <= 0.0001f) return;

        PoseStack ps = e.getPoseStack();
        ps.translate(HAND_X * factor, HAND_RAISE * factor, 0f);
        ps.mulPose(Axis.YP.rotationDegrees(HAND_YAW * factor));
        ps.mulPose(Axis.XP.rotationDegrees(HAND_TILT * factor));
    }

    // ── 3인칭 차단 ──
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (ALLOW_THIRD_PERSON_IN_CREATIVE && (p.isCreative() || p.isSpectator())) return;

        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.gui.setOverlayMessage(Component.literal("바디캠 시점은 변경할 수 없습니다"), false);
        }
    }
}