package numunun.bodycamcomponent.loadout;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import numunun.bodycamcomponent.config.BodyCamConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 총기 부착물: 랜덤 장착, 이전 총의 부착물 옮겨 달기 (randomAttachments = false일 때)
final class LoadoutAttachments {
    private static final String AUTO_TAG = "BodyCamAuto";  // 다음 호환 총에 자동 장착할 부착물 표시
    private static final AttachmentType[] RANDOM_SLOTS = {
            AttachmentType.SCOPE, AttachmentType.MUZZLE, AttachmentType.GRIP,
            AttachmentType.LASER, AttachmentType.STOCK
    };

    private LoadoutAttachments() {}

    // 총마다 장착 가능한 부착물 목록 (같은 총은 다시 계산하지 않음, 경기 시작마다 초기화)
    private record Compat(Map<AttachmentType, List<ResourceLocation>> byType, List<ResourceLocation> mags) {}

    private static final Map<ResourceLocation, Compat> compatCache = new HashMap<>();

    static void clearCache() {
        compatCache.clear();
    }

    // ── 랜덤 부착물 ──
    static void randomize(ItemStack gun, GunData data, RandomSource r) {
        if (!(gun.getItem() instanceof IGun iGun) || iGun.hasAttachmentLock(gun)) return;
        Compat compat = compatCache.computeIfAbsent(iGun.getGunId(gun), id -> buildCompat(iGun, gun));

        // 슬롯마다 확률적으로 비우거나 랜덤 장착
        for (AttachmentType type : RANDOM_SLOTS) {
            List<ResourceLocation> list = compat.byType().get(type);
            if (list == null || list.isEmpty()) continue;
            if (r.nextFloat() < emptyChance(type)) continue;
            install(iGun, gun, list.get(r.nextInt(list.size())));
        }
        if (!compat.mags().isEmpty()) install(iGun, gun, compat.mags().get(r.nextInt(compat.mags().size())));

        // 확장 탄창 용량까지 꽉 채우기
        iGun.setCurrentAmmoCount(gun, AttachmentDataUtils.getAmmoCountWithAttachment(gun, data));
    }

    // 이 총에 달 수 있는 부착물을 슬롯별로 모으기
    private static Compat buildCompat(IGun iGun, ItemStack gun) {
        List<? extends String> blacklist = BodyCamConfig.ATTACHMENT_BLACKLIST.get();
        Map<AttachmentType, List<ResourceLocation>> byType = new EnumMap<>(AttachmentType.class);
        List<ResourceLocation> mags = new ArrayList<>();
        for (Map.Entry<ResourceLocation, CommonAttachmentIndex> e : TimelessAPI.getAllCommonAttachmentIndex()) {
            if (blacklist.contains(e.getKey().toString())) continue;
            ItemStack att = AttachmentItemBuilder.create().setId(e.getKey()).build();
            if (!iGun.allowAttachment(gun, att)) continue;

            AttachmentType type = e.getValue().getType();
            if (type == AttachmentType.EXTENDED_MAG) {
                if (e.getValue().getData() != null && e.getValue().getData().getExtendedMagLevel() == 1) {
                    mags.add(e.getKey());  // 확장 탄창은 1단계만
                }
            } else {
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        return new Compat(byType, mags);
    }

    private static void install(IGun iGun, ItemStack gun, ResourceLocation id) {
        iGun.installAttachment(gun, AttachmentItemBuilder.create().setId(id).build());
    }

    private static float emptyChance(AttachmentType type) {
        return LoadoutItems.floatValue(BodyCamConfig.ATTACHMENT_EMPTY_CHANCE.get(),
                type.name().toLowerCase(Locale.ROOT), 0f);
    }

    // ── 이전 총의 부착물 옮겨 달기 ──
    // 총에 장착된 부착물 떼어내기 (기본 부착물 제외)
    static List<ItemStack> extract(ItemStack gun) {
        List<ItemStack> out = new ArrayList<>();
        if (!(gun.getItem() instanceof IGun iGun)) return out;
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue;
            ItemStack att = iGun.getAttachment(gun, type);
            if (!att.isEmpty()) out.add(att.copy());
        }
        return out;
    }

    static boolean isAutoMarked(ItemStack s) {
        return s.hasTag() && s.getTag().getBoolean(AUTO_TAG);
    }

    // 새 총에 장착, 안 되면 표시를 붙여서 인벤토리로
    static void installOrGive(ServerPlayer p, List<ItemStack> guns, List<ItemStack> attachments) {
        for (ItemStack att : attachments) {
            if (!tryInstall(guns, att)) {
                att.getOrCreateTag().putBoolean(AUTO_TAG, true);
                LoadoutItems.give(p, att);
            }
        }
    }

    // 인벤토리에서 표시된 부착물만 새 총에 자동 장착 (직접 주운 부착물은 건드리지 않음)
    static void autoInstallFromInventory(ServerPlayer p, List<ItemStack> guns) {
        if (guns.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !isAutoMarked(s)) continue;
            if (tryInstall(guns, s)) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // 부착물 하나를 호환되는 총의 빈 슬롯에 장착 시도
    private static boolean tryInstall(List<ItemStack> guns, ItemStack att) {
        if (!(att.getItem() instanceof IAttachment iAtt)) return false;
        AttachmentType type = iAtt.getType(att);
        for (ItemStack gun : guns) {
            if (!(gun.getItem() instanceof IGun iGun)) continue;
            if (iGun.hasAttachmentLock(gun)) continue;
            if (!iGun.allowAttachment(gun, att)) continue;
            if (!iGun.getAttachment(gun, type).isEmpty()) continue;
            iGun.installAttachment(gun, unmarkedCopy(att));
            return true;
        }
        return false;
    }

    private static ItemStack unmarkedCopy(ItemStack s) {
        ItemStack c = s.copyWithCount(1);
        if (c.hasTag()) c.getTag().remove(AUTO_TAG);
        return c;
    }
}