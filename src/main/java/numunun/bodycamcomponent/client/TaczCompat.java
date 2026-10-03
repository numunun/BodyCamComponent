package numunun.bodycamcomponent.client;

import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import net.minecraft.client.player.LocalPlayer;

final class TaczCompat {
    private TaczCompat() {}

    static float aimingProgress(LocalPlayer player, float partialTick) {
        return IClientPlayerGunOperator.fromLocalPlayer(player).getClientAimingProgress(partialTick);
    }
}