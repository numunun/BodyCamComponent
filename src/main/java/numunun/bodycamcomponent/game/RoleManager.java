package numunun.bodycamcomponent.game;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.network.AbilitySyncPacket;
import numunun.bodycamcomponent.network.BodyCamNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

// 직급: 팀 대칭 배정, 경기 중 합류자 배정, 직급 능력 (팀장 체력, 작전관 시야)
final class RoleManager {
    private static final UUID LEADER_HEALTH_ID = UUID.fromString("6f1f7a52-3c1e-4c55-9b7e-2b1a4c8e9d01");

    private static final Map<UUID, String> playerRoles = new HashMap<>();
    private static final List<String> roleOrder = new ArrayList<>();  // 모든 팀이 공유하는 직급 순서

    private RoleManager() {}

    // 경기 시작 시 초기화 (보고서를 위해 경기 종료 후에도 유지)
    static void reset() {
        playerRoles.clear();
        roleOrder.clear();
    }

    @Nullable
    static String roleOf(ServerPlayer p) { return playerRoles.get(p.getUUID()); }

    @Nullable
    static String roleOf(UUID id) { return playerRoles.get(id); }

    // 팀장: 팀전에서 직급 목록의 첫 번째 직급
    static boolean isLeader(ServerPlayer p) {
        if (!GameManager.mode().isTeamMode()) return false;
        String role = roleOf(p);
        return role != null && role.equals(roleList().get(0));
    }

    static boolean isOperator(ServerPlayer p) {
        return BodyCamConfig.OPERATOR_ROLE.get().equals(roleOf(p));
    }

    static boolean isRecon(ServerPlayer p) {
        return BodyCamConfig.RECON_ROLE.get().equals(roleOf(p));
    }

    // ── 배정 ──
    private static List<String> roleList() {
        List<String> roles = new ArrayList<>(BodyCamConfig.ROLE_TITLES.get());
        if (roles.isEmpty()) roles.add("대원");
        return roles;
    }

    // 모든 팀이 같은 직급 구성을 받음. 팀 안에서 누가 어떤 직급을 받을지만 랜덤
    static void assign(MinecraftServer server) {
        List<String> roles = roleList();
        Random rnd = new Random();

        // 첫 직급(팀장)은 맨 앞 고정, 나머지는 섞어서 모든 팀이 공유
        roleOrder.clear();
        roleOrder.add(roles.get(0));
        List<String> rest = new ArrayList<>(roles.subList(1, roles.size()));
        Collections.shuffle(rest, rnd);
        roleOrder.addAll(rest);

        boolean teamMode = GameManager.mode().isTeamMode();
        Map<String, List<ServerPlayer>> byTeam = new LinkedHashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!GameManager.isParticipant(p)) continue;
            String key = teamMode && p.getTeam() != null ? p.getTeam().getName() : "";
            byTeam.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        for (List<ServerPlayer> group : byTeam.values()) {
            List<ServerPlayer> members = new ArrayList<>(group);
            Collections.shuffle(members, rnd);
            for (int i = 0; i < members.size(); i++) {
                String role = teamMode ? roleAt(i) : roles.get(rnd.nextInt(roles.size()));
                playerRoles.put(members.get(i).getUUID(), role);
            }
        }
    }

    // 인원이 직급 수보다 많으면 첫 직급을 제외하고 다시 순환
    private static String roleAt(int index) {
        if (index < roleOrder.size()) return roleOrder.get(index);
        if (roleOrder.size() <= 1) return roleOrder.get(0);
        return roleOrder.get(1 + (index - 1) % (roleOrder.size() - 1));
    }

    // 경기 중 들어온 사람: 자기 팀의 다음 순번 직급 (재접속이면 원래 직급 유지)
    static void ensureRole(ServerPlayer p) {
        if (playerRoles.containsKey(p.getUUID())) return;

        if (!GameManager.mode().isTeamMode() || p.getTeam() == null || roleOrder.isEmpty()) {
            List<String> roles = roleList();
            playerRoles.put(p.getUUID(), roles.get(new Random().nextInt(roles.size())));
            return;
        }
        String team = p.getTeam().getName();
        int count = 0;
        for (UUID id : playerRoles.keySet()) {
            if (team.equals(ScoreManager.teamOf(id))) count++;
        }
        playerRoles.put(p.getUUID(), roleAt(count));
    }

    // ── 능력 ──
    // 팀장 추가 체력 (스폰할 때마다)
    static void applyLeaderHealth(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) return;
        inst.removeModifier(LEADER_HEALTH_ID);
        int bonus = BodyCamConfig.LEADER_HEALTH_BONUS.get();
        if (bonus > 0 && isLeader(p)) {
            inst.addTransientModifier(new AttributeModifier(LEADER_HEALTH_ID, "bodycam_leader_health",
                    bonus, AttributeModifier.Operation.ADDITION));
        }
    }

    // 작전관: 아군 벽 너머 표시 (클라이언트에 알림)
    static void sendAbility(ServerPlayer p) {
        BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new AbilitySyncPacket(isOperator(p)));
    }

    // 경기 종료: 능력 해제, 원래 체력으로
    static void clear(MinecraftServer server) {
        BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new AbilitySyncPacket(false));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            AttributeInstance inst = p.getAttribute(Attributes.MAX_HEALTH);
            if (inst != null && inst.getModifier(LEADER_HEALTH_ID) != null) {
                inst.removeModifier(LEADER_HEALTH_ID);
                if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
            }
        }
    }
}