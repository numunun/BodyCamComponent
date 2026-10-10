package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import numunun.bodycamcomponent.entity.CorpseEntity;

import java.util.UUID;

public class CorpseRenderer extends EntityRenderer<CorpseEntity> {
    private final PlayerModel<LivingEntity> wideModel;
    private final PlayerModel<LivingEntity> slimModel;

    private static final float SCALE = 1.0f;  // 시체 크기 (살아 있는 플레이어는 약 0.94)

    public CorpseRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        wideModel = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false);
        slimModel = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        shadowRadius = 0.5f;
    }

    private record Skin(ResourceLocation texture, boolean slim) {}

    private Skin skinOf(CorpseEntity e) {
        UUID id = e.getOwner().orElse(null);
        if (id == null) return new Skin(DefaultPlayerSkin.getDefaultSkin(), false);

        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        PlayerInfo info = conn == null ? null : conn.getPlayerInfo(id);
        if (info != null) return new Skin(info.getSkinLocation(), "slim".equals(info.getModelName()));
        return new Skin(DefaultPlayerSkin.getDefaultSkin(id), "slim".equals(DefaultPlayerSkin.getSkinModelName(id)));
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity e) {
        return skinOf(e).texture();
    }

    @Override
    public void render(CorpseEntity e, float yaw, float partialTick, PoseStack ps, MultiBufferSource buffers, int light) {
        Skin skin = skinOf(e);
        PlayerModel<LivingEntity> model = skin.slim() ? slimModel : wideModel;

        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180f - e.getYRot()));
        ps.scale(SCALE, SCALE, SCALE);
        ps.translate(0, 0.13, 0);                   // 등이 바닥에 파묻히지 않게
        ps.mulPose(Axis.XP.rotationDegrees(90f));    // 등을 대고 눕기 (얼굴이 위로)
        ps.scale(-1f, -1f, 1f);
        ps.translate(0, -1.501, 0);
        model.renderToBuffer(ps, buffers.getBuffer(model.renderType(skin.texture())),
                light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        ps.popPose();
    }
}