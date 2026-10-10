package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.compat.PlayerReviveCompat;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.DominationSyncPacket;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 점령전: 거점 점령 판정, 점수, 줄다리기 HUD 동기화, 파티클 테두리
final class DominationManager {
    static final int MAX_POINTS = 5;
    private static final int RADIUS = 3;            // 7x7
    private static final int Y_DOWN = 1;
    private static final int Y_UP = 4;
    private static final int CAPTURE_TICKS = 100;   // 혼자 점령 시 5초
    private static final int SECURE_TICKS = 90;     // 이 이상이어야 점수 획득
    private static final int SCORE_INTERVAL = 100;  // 5초마다 거점당 1점

    private static final class PointState {
        String owner;
        String capturing;
        int progress;
        boolean contested;
    }

    private static final Map<String, PointState> states = new HashMap<>();
    private static final Map<UUID, Integer> captureTicks = new HashMap<>();
    private static String leftTeam = "";
    private static String rightTeam = "";
    private static int ticks = 0;

    private DominationManager() {}

    static void start(MinecraftServer server) {
        stop();
        captureTicks.clear();
        ticks = 0;
        GameSettings s = GameSettings.get(server);
        for (GameSettings.CapturePoint pt : s.points) states.put(pt.name(), new PointState());

        // 줄다리기 바의 왼쪽/오른쪽 팀 (이름순, 예: blue 왼쪽, red 오른쪽)
        List<String> teams = server.getScoreboard().getPlayerTeams().stream()
                .map(PlayerTeam::getName).filter(s.teamSpawns::containsKey).sorted().toList();
        leftTeam = teams.size() > 0 ? teams.get(0) : "";
        rightTeam = teams.size() > 1 ? teams.get(1) : "";
    }

    // 점령 기여 기록은 결과 발표를 위해 다음 경기 시작 전까지 유지
    static void stop() {
        boolean had = !states.isEmpty();
        states.clear();
        if (had) BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), DominationSyncPacket.EMPTY);
    }

    static int captureSeconds(UUID id) {
        return captureTicks.getOrDefault(id, 0) / 20;
    }

    static void tick(MinecraftServer server) {
        ticks++;
        GameSettings s = GameSettings.get(server);
        for (GameSettings.CapturePoint pt : s.points) tickPoint(server, pt);
        if (ticks % SCORE_INTERVAL == 0 && scoreTick(server)) return;  // 승리로 종료됨
        if (ticks % 5 == 0) {
            syncHud(s);
            showActionBars(server, s);
        }
        if (ticks % 10 == 0) drawOutlines(server, s);
    }

    // ── 점령 판정 ──
    private static boolean canCapture(ServerPlayer p) {
        return GameManager.isParticipant(p) && !p.isSpectator() && p.isAlive()
                && p.getTeam() != null && !PlayerReviveCompat.isBleeding(p);
    }

    private static boolean isOnPoint(ServerPlayer p, GameSettings.CapturePoint pt) {
        if (!p.level().dimension().equals(pt.dim())) return false;
        if (!p.onGround()) return false;  // 점프 중에는 점령 불가
        var feet = p.blockPosition();
        int dy = feet.getY() - (pt.center().getY() + 1);
        return dy >= -Y_DOWN && dy <= Y_UP
                && Math.abs(feet.getX() - pt.center().getX()) <= RADIUS
                && Math.abs(feet.getZ() - pt.center().getZ()) <= RADIUS;
    }

    private static void tickPoint(MinecraftServer server, GameSettings.CapturePoint pt) {
        PointState st = states.get(pt.name());
        ServerLevel lvl = server.getLevel(pt.dim());
        if (st == null || lvl == null) return;

        Map<String, Integer> counts = new HashMap<>();
        List<ServerPlayer> standing = new ArrayList<>();
        for (ServerPlayer p : lvl.players()) {
            if (!canCapture(p) || !isOnPoint(p, pt)) continue;
            counts.merge(p.getTeam().getName(), 1, Integer::sum);
            standing.add(p);
        }

        if (counts.size() >= 2) {
            st.contested = true;  // 대치: 진행도 그대로 멈춤
            return;
        }
        st.contested = false;
        if (counts.isEmpty()) return;

        Map.Entry<String, Integer> entry = counts.entrySet().iterator().next();
        String team = entry.getKey();
        int n = entry.getValue();
        int before = st.progress;

        if (st.owner != null) {
            if (team.equals(st.owner)) {
                st.capturing = st.owner;
                st.progress = Math.min(CAPTURE_TICKS, st.progress + n);
            } else {
                st.progress -= n;
                if (st.progress <= 0) {
                    st.owner = null;
                    st.capturing = team;
                    st.progress = 0;
                }
            }
        } else if (st.capturing == null || team.equals(st.capturing)) {
            st.capturing = team;
            st.progress = Math.min(CAPTURE_TICKS, st.progress + n);
            if (st.progress >= CAPTURE_TICKS) {
                st.owner = team;
                GameManager.broadcast(server, Component.literal("[거점 " + pt.name() + "] ").withStyle(ChatFormatting.GOLD)
                        .append(teamLabel(server, team))
                        .append(Component.literal(" 점령").withStyle(ChatFormatting.WHITE)));
                lvl.playSound(null, pt.center(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1f, 1f);
            }
        } else {
            st.progress -= n;
            if (st.progress <= 0) {
                st.progress = 0;
                st.capturing = team;
            }
        }

        if (st.progress != before) {
            for (ServerPlayer p : standing) captureTicks.merge(p.getUUID(), 1, Integer::sum);
        }
    }

    // ── 점수 ──
    private static boolean scoreTick(MinecraftServer server) {
        Map<String, Integer> gain = new HashMap<>();
        for (PointState st : states.values()) {
            if (st.owner != null && st.progress >= SECURE_TICKS) gain.merge(st.owner, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> g : gain.entrySet()) {
            int total = GameManager.addTeamScore(server, g.getKey(), g.getValue());
            if (total >= GameManager.target()) {
                GameManager.end(server, Component.literal(GameManager.teamDisplay(server, g.getKey()) + " 승리"));
                return true;
            }
        }
        return false;
    }

    // ── 줄다리기 HUD ──
    // 중립 = 0.5, 왼쪽 팀이 완전히 확보 = 1.0, 오른쪽 팀이 완전히 확보 = 0.0
    private static float leftShare(PointState st) {
        String side = st.owner != null ? st.owner : st.capturing;
        if (side == null) return 0.5f;
        float share = 0.5f + 0.5f * st.progress / CAPTURE_TICKS;
        return side.equals(leftTeam) ? share : 1f - share;
    }

    private static void syncHud(GameSettings s) {
        List<DominationSyncPacket.Point> list = new ArrayList<>();
        for (GameSettings.CapturePoint pt : s.points) {
            PointState st = states.get(pt.name());
            if (st != null) list.add(new DominationSyncPacket.Point(pt.name(), leftShare(st), st.contested));
        }
        BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new DominationSyncPacket(leftTeam, rightTeam, list));
    }

    // 거점 위에 선 사람에게 액션바로 상태 표시
    private static void showActionBars(MinecraftServer server, GameSettings s) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!canCapture(p)) continue;
            for (GameSettings.CapturePoint pt : s.points) {
                if (!isOnPoint(p, pt)) continue;
                PointState st = states.get(pt.name());
                if (st == null) break;

                MutableComponent msg = Component.literal("거점 " + pt.name() + "  ").withStyle(ChatFormatting.GOLD);
                String side = st.owner != null ? st.owner : st.capturing;
                if (st.contested) {
                    msg.append(Component.literal("대치 중").withStyle(ChatFormatting.YELLOW));
                } else if (st.owner != null && st.progress >= CAPTURE_TICKS) {
                    msg.append(teamLabel(server, st.owner)).append(Component.literal(" 확보").withStyle(ChatFormatting.WHITE));
                } else if (side != null) {
                    msg.append(teamLabel(server, side))
                            .append(Component.literal(" " + st.progress * 100 / CAPTURE_TICKS + "%").withStyle(ChatFormatting.WHITE));
                } else {
                    msg.append(Component.literal("중립").withStyle(ChatFormatting.GRAY));
                }
                p.displayClientMessage(msg, true);
                break;
            }
        }
    }

    // ── 파티클 테두리 (소유 팀 색) ──
    private static void drawOutlines(MinecraftServer server, GameSettings s) {
        for (GameSettings.CapturePoint pt : s.points) {
            ServerLevel lvl = server.getLevel(pt.dim());
            if (lvl == null) continue;
            PointState st = states.get(pt.name());

            int rgb = 0xD9D9D9;
            if (st != null && st.owner != null) {
                Integer c = teamColor(server, st.owner).getColor();
                if (c != null) rgb = c;
            }
            DustParticleOptions dust = new DustParticleOptions(
                    new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f), 1.0f);

            double y = pt.center().getY() + 1.02;
            double minX = pt.center().getX() - RADIUS, maxX = pt.center().getX() + RADIUS + 1;
            double minZ = pt.center().getZ() - RADIUS, maxZ = pt.center().getZ() + RADIUS + 1;
            for (double x = minX; x <= maxX + 1e-6; x += 0.25) {
                lvl.sendParticles(dust, x, y, minZ, 1, 0, 0, 0, 0);
                lvl.sendParticles(dust, x, y, maxZ, 1, 0, 0, 0, 0);
            }
            for (double z = minZ; z <= maxZ + 1e-6; z += 0.25) {
                lvl.sendParticles(dust, minX, y, z, 1, 0, 0, 0, 0);
                lvl.sendParticles(dust, maxX, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    // ── 도우미 ──
    private static ChatFormatting teamColor(MinecraftServer server, String team) {
        PlayerTeam t = server.getScoreboard().getPlayerTeam(team);
        return t != null ? t.getColor() : ChatFormatting.WHITE;
    }

    private static MutableComponent teamLabel(MinecraftServer server, String team) {
        return Component.literal(GameManager.teamDisplay(server, team)).withStyle(teamColor(server, team), ChatFormatting.BOLD);
    }
}