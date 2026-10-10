package numunun.bodycamcomponent.compat;

import net.minecraft.world.entity.player.Player;

import numunun.bodycamcomponent.downed.DownedManager;

import java.lang.reflect.Method;

// PlayerRevive 빈사 상태 확인 (PlayerRevive 없어도 동작)
public final class PlayerReviveCompat {
    private static boolean checked = false;
    private static Method isBleeding;

    private PlayerReviveCompat() {}

    public static boolean isBleeding(Player p) {
        if (DownedManager.isDowned(p)) return true;
        if (!checked) {
            checked = true;
            try {
                Class<?> c = Class.forName("team.creative.playerrevive.server.PlayerReviveServer");
                isBleeding = c.getMethod("isBleeding", Player.class);
            } catch (Throwable t) {
                isBleeding = null;
            }
        }
        if (isBleeding == null) return false;
        try {
            return (boolean) isBleeding.invoke(null, p);
        } catch (Throwable t) {
            isBleeding = null;
            return false;
        }
    }
}