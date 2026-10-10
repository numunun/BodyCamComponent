package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.PingRequestPacket;
import numunun.bodycamcomponent.util.Safe;
import org.lwjgl.glfw.GLFW;

// 핑 키 (기본: 마우스 휠 클릭)
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class PingKey {
    private static final double RANGE = 200.0;

    public static final KeyMapping KEY = new KeyMapping("key.bodycamcomponent.ping",
            InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "key.categories.bodycamcomponent");

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        while (KEY.consumeClick()) Safe.run("핑 보내기", PingKey::sendPing);
    }

    private static void sendPing() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null) return;

        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 end = eye.add(look.scale(RANGE));

        BlockHitResult block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        Vec3 hitPos = block.getType() == HitResult.Type.MISS ? end : block.getLocation();

        // 블록보다 가까운 플레이어를 가리켰는지
        AABB box = p.getBoundingBox().expandTowards(look.scale(RANGE)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(p, eye, hitPos, box,
                ent -> ent instanceof Player && ent != p && !ent.isSpectator(), eye.distanceToSqr(hitPos));

        int entityId = -1;
        if (entityHit != null) {
            hitPos = entityHit.getLocation();
            entityId = entityHit.getEntity().getId();
        } else if (block.getType() == HitResult.Type.MISS) {
            return;  // 허공은 무시
        }
        BodyCamNetwork.CHANNEL.sendToServer(new PingRequestPacket(hitPos.x, hitPos.y, hitPos.z, entityId));
    }
}