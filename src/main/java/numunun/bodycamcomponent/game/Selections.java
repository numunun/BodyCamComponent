package numunun.bodycamcomponent.game;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// 플레이어별 구역 선택 상태
public final class Selections {
    private static final Map<UUID, BlockPos[]> MAP = new HashMap<>();

    private Selections() {}

    public static void setPos(ServerPlayer p, int index, BlockPos pos) {
        BlockPos[] arr = MAP.computeIfAbsent(p.getUUID(), k -> new BlockPos[2]);
        if (pos.equals(arr[index])) return;
        arr[index] = pos;
        p.sendSystemMessage(Component.literal(String.format("지점 %d: (%d, %d, %d)",
                index + 1, pos.getX(), pos.getY(), pos.getZ())));
    }

    @Nullable
    public static Region get(ServerPlayer p) {
        BlockPos[] arr = MAP.get(p.getUUID());
        if (arr == null || arr[0] == null || arr[1] == null) return null;
        return Region.of(p.level().dimension(), arr[0], arr[1]);
    }
}