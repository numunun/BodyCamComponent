package numunun.bodycamcomponent.compat;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.game.GameManager;

// 빈사 상태나 일시정지 중에는 사격, 재장전, 아이템 사용 차단
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID)
public final class ActionRestrictions {

    private static boolean blocked(Entity e) {
        if (!(e instanceof Player p)) return false;
        return PlayerReviveCompat.isBleeding(p) || GameManager.isPaused();
    }

    @SubscribeEvent
    public static void onShoot(GunShootEvent e) {
        if (blocked(e.getShooter())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFire(GunFireEvent e) {
        if (blocked(e.getShooter())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMelee(GunMeleeEvent e) {
        if (blocked(e.getShooter())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onReload(GunReloadEvent e) {
        if (blocked(e.getEntity())) e.setCanceled(true);
    }

    // SBW 수류탄 등 우클릭 아이템
    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (blocked(e.getEntity())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        }
    }
}