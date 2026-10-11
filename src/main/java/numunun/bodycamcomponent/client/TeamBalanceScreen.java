package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.TeamActionPacket;
import numunun.bodycamcomponent.network.TeamScreenData;
import numunun.bodycamcomponent.network.TeamScreenPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 팀 배정 화면: 왼쪽 팀 / 오른쪽 팀 / 제외. 관리자는 이름을 눌러 옮기고 자동 배정, 섞기, 확정
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public class TeamBalanceScreen extends Screen {
    private static final int COL_W = 130;
    private static final int ROW_H = 12;
    private static final int TOP = 40;

    private record Hit(int x1, int y1, int x2, int y2, UUID id) {}

    private final List<Hit> hits = new ArrayList<>();
    private int seenVersion = -1;
    private boolean seenCanEdit = false;

    public TeamBalanceScreen() {
        super(Component.literal("팀 배정"));
    }

    // 서버가 화면을 띄우라고 하면 다음 틱에 열기
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !TeamScreenData.openRequested) return;
        TeamScreenData.openRequested = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof TeamBalanceScreen)) mc.setScreen(new TeamBalanceScreen());
    }

    @Override
    protected void init() {
        TeamScreenPacket d = TeamScreenData.current;
        seenCanEdit = d != null && d.canEdit();
        if (!seenCanEdit) return;

        int cx = width / 2;
        int y = height - 32;
        addRenderableWidget(Button.builder(Component.literal("자동 배정"), b -> send(TeamActionPacket.AUTO))
                .bounds(cx - 155, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("섞기"), b -> send(TeamActionPacket.SHUFFLE))
                .bounds(cx - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("확정"), b -> send(TeamActionPacket.CONFIRM))
                .bounds(cx + 55, y, 100, 20).build());
    }

    // 수정 권한이 바뀌면(경기 시작 등) 버튼을 다시 만듦
    @Override
    public void tick() {
        TeamScreenPacket d = TeamScreenData.current;
        if (d != null && TeamScreenData.version != seenVersion) {
            seenVersion = TeamScreenData.version;
            if (d.canEdit() != seenCanEdit) rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        TeamScreenPacket d = TeamScreenData.current;
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (d == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        int cx = width / 2;
        int[] xs = {cx - COL_W * 3 / 2, cx - COL_W / 2, cx + COL_W / 2};
        String[] heads = {d.leftName(), d.rightName(), "제외"};
        int[] colors = {0xFF000000 | d.leftColor(), 0xFF000000 | d.rightColor(), 0xFF888888};

        hits.clear();
        for (int side = 0; side < 3; side++) {
            int x = xs[side];
            int total = 0;
            int count = 0;
            int y = TOP + 14;
            for (TeamScreenPacket.Entry e : d.entries()) {
                if (e.side() != side) continue;
                total += e.rating();
                count++;
                boolean hover = d.canEdit() && mouseX >= x && mouseX < x + COL_W - 6 && mouseY >= y - 1 && mouseY < y + ROW_H - 1;
                if (hover) g.fill(x - 2, y - 2, x + COL_W - 6, y + ROW_H - 2, 0x40FFFFFF);
                g.drawString(font, e.name(), x, y, 0xFFFFFFFF, true);
                String r = String.valueOf(e.rating());
                g.drawString(font, r, x + COL_W - 10 - font.width(r), y, 0xFFAAAAAA, true);
                hits.add(new Hit(x - 2, y - 2, x + COL_W - 6, y + ROW_H - 2, e.id()));
                y += ROW_H;
            }
            String head = side == 2 ? heads[side] + " (" + count + ")" : heads[side] + "  " + count + "명 · 합계 " + total;
            g.drawString(font, head, x, TOP, colors[side], true);
            g.fill(x, TOP + 10, x + COL_W - 10, TOP + 11, colors[side]);
        }

        String hint = d.canEdit() ? "이름을 누르면 다음 칸으로 옮겨집니다 (왼쪽 → 오른쪽 → 제외)" : "보기 전용 (수정은 관리자만, 경기 중에는 불가)";
        g.drawCenteredString(font, hint, cx, height - 48, 0xFF999999);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        TeamScreenPacket d = TeamScreenData.current;
        if (d != null && d.canEdit() && button == 0) {
            for (Hit h : hits) {
                if (mouseX >= h.x1() && mouseX < h.x2() && mouseY >= h.y1() && mouseY < h.y2()) {
                    BodyCamNetwork.CHANNEL.sendToServer(new TeamActionPacket(TeamActionPacket.MOVE, h.id()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        BodyCamNetwork.CHANNEL.sendToServer(new TeamActionPacket(TeamActionPacket.CLOSE, TeamActionPacket.NONE));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void send(int action) {
        BodyCamNetwork.CHANNEL.sendToServer(new TeamActionPacket(action, TeamActionPacket.NONE));
    }
}