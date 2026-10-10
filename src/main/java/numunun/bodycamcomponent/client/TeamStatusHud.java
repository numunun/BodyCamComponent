package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Team;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import numunun.bodycamcomponent.downed.DownedManager;

import java.util.ArrayList;
import java.util.List;

// 오른쪽 팀 상태판: 이름 ■ Online / No Signal
public final class TeamStatusHud {
    private static final int LINE_H = 11;

    private TeamStatusHud() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        Team team = mc.player.getTeam();
        ClientPacketListener conn = mc.getConnection();
        if (team == null || conn == null) return;

        List<String> names = new ArrayList<>(team.getPlayers());
        if (names.isEmpty()) return;
        names.sort(String.CASE_INSENSITIVE_ORDER);

        Font font = mc.font;
        int nameW = 0;
        for (String n : names) nameW = Math.max(nameW, font.width(n));
        int statusW = font.width("No Signal");
        int panelW = nameW + 12 + 6 + statusW;

        int x = w - panelW - 6;
        int y = h / 2 - names.size() * LINE_H / 2;
        g.fill(x - 4, y - 4, x + panelW + 4, y + names.size() * LINE_H + 2, 0x60000000);

        for (String name : names) {
            Status st = statusOf(mc, conn, name);
            int dot = switch (st) {
                case ONLINE -> 0xFF55FF55;
                case DOWNED -> 0xFFFFCC33;
                case NO_SIGNAL -> 0xFF777777;
            };
            String status = switch (st) {
                case ONLINE -> "Online";
                case DOWNED -> "Downed";
                case NO_SIGNAL -> "No Signal";
            };
            int textColor = switch (st) {
                case ONLINE -> 0xFFDDDDDD;
                case DOWNED -> 0xFFFFDD88;
                case NO_SIGNAL -> 0xFF888888;
            };

            g.drawString(font, name, x, y, 0xFFFFFFFF, true);
            int sx = x + nameW + 12;
            g.fill(sx, y + 2, sx + 4, y + 6, dot);
            g.drawString(font, status, sx + 7, y, textColor, true);
            y += LINE_H;
        }
    }

    private enum Status { ONLINE, DOWNED, NO_SIGNAL }

    // 접속 끊김·리스폰 대기·사망 = No Signal, 빈사 = Downed, 나머지 = Online
    private static Status statusOf(Minecraft mc, ClientPacketListener conn, String name) {
        PlayerInfo info = conn.getPlayerInfo(name);
        if (info == null || info.getGameMode() == GameType.SPECTATOR) return Status.NO_SIGNAL;
        if (DownedManager.isClientDowned(info.getProfile().getId())) return Status.DOWNED;
        for (Player p : mc.level.players()) {
            if (p.getScoreboardName().equals(name)) return p.isDeadOrDying() ? Status.NO_SIGNAL : Status.ONLINE;
        }
        return Status.ONLINE;  // 멀리 있어서 안 보이는 팀원
    }
}