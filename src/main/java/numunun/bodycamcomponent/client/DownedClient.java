package numunun.bodycamcomponent.client;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.downed.DownedManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// 빈사 상태인 플레이어를 내 화면에서도 기어가는 자세로
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class DownedClient {
    private static final Set<UUID> forced = new HashSet<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !e.side.isClient()) return;
        Player p = e.player;
        if (DownedManager.isDowned(p)) {
            p.setForcedPose(Pose.SWIMMING);
            p.setPose(Pose.SWIMMING);
            forced.add(p.getUUID());
        } else if (forced.remove(p.getUUID())) {
            p.setForcedPose(null);
            p.setPose(Pose.STANDING);  // 기어가기 자세가 남지 않게
        }
    }
}