package numunun.bodycamcomponent.loadout;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.game.GameManager;

import java.util.List;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID)
public final class LoadoutEvents {

    // LOWEST: PlayerRevive가 빈사 상태로 전환(사망 취소)한 경우엔 실행되지 않음
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent e) {
        if (!LoadoutManager.isEnabled()) return;
        if (e.getEntity() instanceof ServerPlayer p) {
            LoadoutManager.stashOnDeath(p);
        }
    }

    // 보관 데이터를 새 몸으로 복사
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone e) {
        if (!e.isWasDeath()) return;
        CompoundTag oldData = e.getOriginal().getPersistentData();
        CompoundTag newData = e.getEntity().getPersistentData();
        for (String key : new String[]{LoadoutManager.STASH_KEY, LoadoutManager.ATTACH_KEY}) {
            if (oldData.contains(key)) newData.put(key, oldData.get(key).copy());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.isEndConquered()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        // 경기 참가자는 GameManager가 리스폰 대기 후 지급
        if (GameManager.isParticipant(p)) return;

        List<ItemStack> guns = LoadoutManager.isEnabled()
                ? LoadoutManager.giveRandomLoadout(p)
                : List.of();
        LoadoutManager.restoreAfterRespawn(p, guns);
    }
}