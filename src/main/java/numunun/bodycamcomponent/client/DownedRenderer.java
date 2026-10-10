package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.compat.PlayerReviveCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 빈사 상태인 다른 플레이어를 "등을 대고 누운 부상자" 자세로 그리기
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class DownedRenderer {
    private static final Map<PlayerRenderer,
            HumanoidArmorLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>, HumanoidModel<AbstractClientPlayer>>>
            armorLayers = new HashMap<>();

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre e) {
        Player p = e.getEntity();
        if (!(p instanceof AbstractClientPlayer ap) || !PlayerReviveCompat.isBleeding(p)) return;
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player && mc.options.getCameraType().isFirstPerson()) return;  // 내 몸은 건드리지 않음

        e.setCanceled(true);
        PlayerRenderer renderer = e.getRenderer();
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        float pt = e.getPartialTick();
        float bodyYaw = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
        poseModel(model, p, pt, bodyYaw);

        PoseStack ps = e.getPoseStack();
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180f - bodyYaw));
        ps.translate(0, 0.13, 0.85);                 // 바닥에 안 묻히게 + 판정 상자 가운데로
        ps.mulPose(Axis.XP.rotationDegrees(-90f));    // 엎드리기
        ps.scale(-1f, -1f, 1f);
        ps.scale(0.9375f, 0.9375f, 0.9375f);          // 바닐라 플레이어 크기
        ps.translate(0, -1.501, 0);

        int light = e.getPackedLight();
        int overlay = LivingEntityRenderer.getOverlayCoords(ap, 0f);  // 맞으면 빨갛게
        model.renderToBuffer(ps, e.getMultiBufferSource().getBuffer(model.renderType(ap.getSkinTextureLocation())),
                light, overlay, 1f, 1f, 1f, 1f);

        armorLayer(renderer).render(ps, e.getMultiBufferSource(), light, ap, 0f, 0f, pt, ap.tickCount + pt, 0f, 0f);
        ps.popPose();
    }

    private static void poseModel(PlayerModel<AbstractClientPlayer> m, Player p, float pt, float bodyYaw) {
        m.setAllVisible(true);
        m.crouching = false;
        m.riding = false;
        m.young = false;
        m.swimAmount = 0f;
        m.attackTime = 0f;
        m.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        m.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        for (ModelPart part : List.of(m.head, m.hat, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg)) {
            part.resetPose();
        }

        // 고개: 앞을 보듯 들고, 보는 방향으로 좌우 회전
        float headYaw = Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot);
        m.head.xRot = -1.1f;
        m.head.yRot = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -60f, 60f) * Mth.DEG_TO_RAD;

        // 포복: 움직이면 팔다리가 번갈아 바닥을 끎
        float pos = p.walkAnimation.position(pt);
        float moving = Mth.clamp(p.walkAnimation.speed(pt) * 4f, 0f, 1f);  // 기어가기는 느려서 증폭
        // 가만히 있을 때도 0.8 쪽으로 치우쳐서 비대칭 자세 유지
        float swing = Mth.sin(pos * 1.0f) * moving + (1f - moving) * 0.8f;

        // 팔: 한쪽은 앞으로 뻗고 한쪽은 옆에 붙임, 기어가면 번갈아
        m.rightArm.zRot = 1.65f + swing * 1.05f;     // 2.7 = 앞으로 뻗음, 0.6 = 옆에 붙임
        m.leftArm.zRot = -1.65f + swing * 1.05f;     // -0.6 = 옆에 붙임, -2.7 = 앞으로 뻗음

        // 다리: 살짝 벌리고 번갈아 굽힘
        m.rightLeg.zRot = 0.12f + Math.max(0f, -swing) * 0.4f;
        m.leftLeg.zRot = -0.12f - Math.max(0f, swing) * 0.4f;

        // 겉옷 레이어도 같은 자세로
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftPants.copyFrom(m.leftLeg);
        m.rightPants.copyFrom(m.rightLeg);
    }

    private static HumanoidArmorLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>, HumanoidModel<AbstractClientPlayer>>
    armorLayer(PlayerRenderer renderer) {
        return armorLayers.computeIfAbsent(renderer, r -> {
            Minecraft mc = Minecraft.getInstance();
            return new HumanoidArmorLayer<>(r,
                    new HumanoidModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                    mc.getModelManager());
        });
    }
}