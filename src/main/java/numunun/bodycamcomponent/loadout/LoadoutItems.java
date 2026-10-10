package numunun.bodycamcomponent.loadout;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

// 아이템 넣어주기 (인벤토리, 핫바, 탄약 상자) + 설정 목록 읽기
final class LoadoutItems {
    private static final ResourceLocation AMMO_BOX_ID = new ResourceLocation("tacz", "ammo_box");
    private static final int MAX_BOX_LEVEL = 2;  // 0 철, 1 금, 2 다이아

    private LoadoutItems() {}

    // 인벤토리 안쪽(10~36번 칸) 우선, 없으면 아무 곳, 그래도 없으면 바닥
    static void give(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 9; i < 36 && !s.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur.isEmpty()) {
                inv.setItem(i, s);
                return;
            }
            mergeInto(cur, s);
        }
        if (!s.isEmpty() && !inv.add(s)) p.drop(s, false);
    }

    // 핫바 앞쪽부터: 같은 아이템이 있으면 겹치고, 없으면 첫 빈칸, 꽉 찼으면 인벤토리로
    static void giveHotbar(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9 && !s.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty()) mergeInto(cur, s);
        }
        for (int i = 0; i < 9 && !s.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, s);
                return;
            }
        }
        give(p, s);
    }

    // 같은 아이템이면 겹칠 수 있는 만큼 옮김
    private static void mergeInto(ItemStack cur, ItemStack s) {
        if (ItemStack.isSameItemSameTags(cur, s) && cur.getCount() < cur.getMaxStackSize()) {
            int move = Math.min(s.getCount(), cur.getMaxStackSize() - cur.getCount());
            cur.grow(move);
            s.shrink(move);
        }
    }

    // 탄약 상자: 양에 맞는 가장 낮은 등급, 다이아로도 넘치면 여러 개
    static void giveAmmoBoxes(ServerPlayer p, ResourceLocation ammoId, int total) {
        int stackSize = TimelessAPI.getCommonAmmoIndex(ammoId)
                .map(CommonAmmoIndex::getStackSize)
                .orElse(64);
        int perLevel = stackSize * SyncConfig.AMMO_BOX_STACK_SIZE.get();
        if (perLevel <= 0) return;  // 용량 0이면 무한 반복 방지

        while (total > 0) {
            int level = 0;
            while (level < MAX_BOX_LEVEL && perLevel * (level + 1) < total) level++;
            int capacity = perLevel * (level + 1);
            int n = Math.min(capacity, total);
            give(p, makeAmmoBox(ammoId, n, level));
            total -= n;
        }
    }

    private static ItemStack makeAmmoBox(ResourceLocation ammoId, int count, int level) {
        Item item = ForgeRegistries.ITEMS.getValue(AMMO_BOX_ID);
        if (!(item instanceof IAmmoBox box)) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        box.setAmmoId(stack, ammoId);
        box.setAmmoCount(stack, count);
        return box.setAmmoLevel(stack, level);
    }

    // ── 설정 목록 "키=값"에서 값 찾기 ──
    static int intValue(List<? extends String> entries, String key, int fallback) {
        String v = find(entries, key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static float floatValue(List<? extends String> entries, String key, float fallback) {
        String v = find(entries, key);
        if (v == null) return fallback;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String find(List<? extends String> entries, String key) {
        for (String e : entries) {
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(key)) return e.substring(eq + 1).trim();
        }
        return null;
    }
}