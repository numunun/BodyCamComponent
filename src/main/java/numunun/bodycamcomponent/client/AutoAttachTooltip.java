package numunun.bodycamcomponent.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.loadout.LoadoutManager;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class AutoAttachTooltip {
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        if (LoadoutManager.isAutoMarked(e.getItemStack())) {
            e.getToolTip().add(Component.literal("다음 호환 총기에 자동 장착됩니다")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
    }
}