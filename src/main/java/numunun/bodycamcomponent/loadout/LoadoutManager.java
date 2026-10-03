package numunun.bodycamcomponent.loadout;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LoadoutManager {
    public static final String STASH_KEY = "bodycam_stash";
    public static final String ATTACH_KEY = "bodycam_attachments";
    public static final String AUTO_TAG = "BodyCamAuto";

    // 지급 대상 총기 종류
    private static final Set<String> PRIMARY_TYPES = Set.of("rifle", "smg", "shotgun", "sniper");
    private static final Set<String> SECONDARY_TYPES = Set.of("pistol");
    // 종류와 상관없이 제외할 총기
    private static final Set<ResourceLocation> BLACKLIST = Set.of(
            new ResourceLocation("tacz", "m134")
    );

    // 종류별 예비 탄창 수
    private static final Map<String, Integer> SPARE_MAGS = Map.of(
            "pistol", 4, "smg", 5, "rifle", 5, "sniper", 4, "shotgun", 6
    );
    // 종류별 최소 예비 탄약 (탄창이 작은 총 보정)
    private static final Map<String, Integer> MIN_SPARE_AMMO = Map.of(
            "pistol", 40, "sniper", 30, "shotgun", 60
    );

    private static final ResourceLocation AMMO_BOX_ID = new ResourceLocation("tacz", "ammo_box");
    private static final int MAX_BOX_LEVEL = 2;  // 0 철, 1 금, 2 다이아

    private static boolean enabled = false;

    private LoadoutManager() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }

    // 사망 시 사라지는 대상품: 총, 탄약, 탄약 상자
    public static boolean isTarget(ItemStack s) {
        return s.getItem() instanceof IGun
                || s.getItem() instanceof IAmmo
                || s.getItem() instanceof IAmmoBox;
    }

    // 사망 후에도 유지되는 물품: 총기 부착물 (갑옷은 슬롯으로 판단)
    public static boolean isKept(ItemStack s) {
        return s.getItem() instanceof IAttachment;
    }

    private static boolean isArmorSlot(int index) {
        return index >= 36 && index < 40;
    }

    // ── 총에 장착된 부착물 떼어내기 (기본 부착물 제외) ──
    private static List<ItemStack> extractAttachments(ItemStack gun) {
        List<ItemStack> out = new ArrayList<>();
        if (!(gun.getItem() instanceof IGun iGun)) return out;
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue;
            ItemStack att = iGun.getAttachment(gun, type);
            if (!att.isEmpty()) out.add(att.copy());
        }
        return out;
    }

    // ── 대상품 제거 + 장착 부착물 회수 ──
    public static List<ItemStack> clearTargets(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<ItemStack> attachments = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isTarget(s)) {
                attachments.addAll(extractAttachments(s));
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return attachments;
    }

    // ── 사망 시: 대상품 제거, 유지 물품과 장착 부착물 보관 ──
    public static void stashOnDeath(ServerPlayer p) {
        List<ItemStack> attachments = clearTargets(p);

        Inventory inv = p.getInventory();
        ListTag stash = new ListTag();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (isArmorSlot(i) || isKept(s)) {
                CompoundTag t = new CompoundTag();
                t.putInt("Slot", i);
                s.save(t);
                stash.add(t);
                inv.setItem(i, ItemStack.EMPTY);
            }
        }

        ListTag attachTag = new ListTag();
        for (ItemStack att : attachments) attachTag.add(att.save(new CompoundTag()));

        CompoundTag data = p.getPersistentData();
        data.put(STASH_KEY, stash);
        data.put(ATTACH_KEY, attachTag);
    }

    // ── 리스폰 시: 보관 물품 반환 + 부착물 새 총에 장착 ──
    public static void restoreAfterRespawn(ServerPlayer p, List<ItemStack> newGuns) {
        CompoundTag data = p.getPersistentData();
        Inventory inv = p.getInventory();

        if (data.contains(STASH_KEY, Tag.TAG_LIST)) {
            ListTag list = data.getList(STASH_KEY, Tag.TAG_COMPOUND);
            for (int k = 0; k < list.size(); k++) {
                CompoundTag t = list.getCompound(k);
                int slot = t.getInt("Slot");
                ItemStack s = ItemStack.of(t);
                if (slot >= 0 && slot < inv.getContainerSize() && inv.getItem(slot).isEmpty()) {
                    inv.setItem(slot, s);
                } else {
                    give(p, s);
                }
            }
            data.remove(STASH_KEY);
        }

        if (data.contains(ATTACH_KEY, Tag.TAG_LIST)) {
            ListTag list = data.getList(ATTACH_KEY, Tag.TAG_COMPOUND);
            List<ItemStack> attachments = new ArrayList<>();
            for (int k = 0; k < list.size(); k++) attachments.add(ItemStack.of(list.getCompound(k)));
            installOrGive(p, newGuns, attachments);
            data.remove(ATTACH_KEY);
        }
        autoInstallFromInventory(p, newGuns);
    }

    // ── 부착물 하나를 호환되는 총에 장착 시도 ──
    // ── 자동 장착 표시 ──
    public static boolean isAutoMarked(ItemStack s) {
        return s.hasTag() && s.getTag().getBoolean(AUTO_TAG);
    }

    private static void markAuto(ItemStack s) {
        s.getOrCreateTag().putBoolean(AUTO_TAG, true);
    }

    private static ItemStack unmarkedCopy(ItemStack s) {
        ItemStack c = s.copyWithCount(1);
        if (c.hasTag()) c.getTag().remove(AUTO_TAG);
        return c;
    }

    // ── 부착물 하나를 호환되는 총에 장착 시도 ──
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

    // ── 직전 총의 부착물: 장착, 안 되면 표시 붙여서 인벤토리로 ──
    public static void installOrGive(ServerPlayer p, List<ItemStack> guns, List<ItemStack> attachments) {
        for (ItemStack att : attachments) {
            if (!tryInstall(guns, att)) {
                markAuto(att);
                give(p, att);
            }
        }
    }

    // ── 인벤토리에서 표시된 부착물만 새 총에 자동 장착 ──
    public static void autoInstallFromInventory(ServerPlayer p, List<ItemStack> guns) {
        if (guns.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !isAutoMarked(s)) continue;
            if (tryInstall(guns, s)) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // ── 랜덤 지급 (지급한 총 목록 반환) ──
    public static List<ItemStack> giveRandomLoadout(ServerPlayer p) {
        List<Map.Entry<ResourceLocation, CommonGunIndex>> primaries = new ArrayList<>();
        List<Map.Entry<ResourceLocation, CommonGunIndex>> secondaries = new ArrayList<>();

        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {
            if (BLACKLIST.contains(entry.getKey())) continue;
            String type = entry.getValue().getType();
            if (PRIMARY_TYPES.contains(type)) primaries.add(entry);
            else if (SECONDARY_TYPES.contains(type)) secondaries.add(entry);
        }

        List<ItemStack> guns = new ArrayList<>();
        RandomSource r = p.getRandom();
        if (!primaries.isEmpty()) guns.add(giveGun(p, primaries.get(r.nextInt(primaries.size())), 0));
        if (!secondaries.isEmpty()) guns.add(giveGun(p, secondaries.get(r.nextInt(secondaries.size())), 1));
        return guns;
    }

    private static ItemStack giveGun(ServerPlayer p, Map.Entry<ResourceLocation, CommonGunIndex> entry, int slot) {
        ResourceLocation id = entry.getKey();
        String type = entry.getValue().getType();
        GunData data = entry.getValue().getGunData();

        List<FireMode> modes = data.getFireModeSet();
        FireMode mode = (modes == null || modes.isEmpty()) ? FireMode.SEMI : modes.get(0);

        ItemStack gun = GunItemBuilder.create()
                .setId(id)
                .setFireMode(mode)
                .setAmmoCount(data.getAmmoAmount())
                .build();

        Inventory inv = p.getInventory();
        if (inv.getItem(slot).isEmpty()) inv.setItem(slot, gun);
        else give(p, gun);

        int mags = SPARE_MAGS.getOrDefault(type, 4);
        int total = Math.max(data.getAmmoAmount() * mags, MIN_SPARE_AMMO.getOrDefault(type, 0));
        giveAmmoBoxes(p, data.getAmmoId(), total);
        return gun;
    }

    // ── 탄약 상자: 양에 맞는 가장 낮은 등급, 다이아로도 넘치면 여러 개 ──
    private static void giveAmmoBoxes(ServerPlayer p, ResourceLocation ammoId, int total) {
        int stackSize = TimelessAPI.getCommonAmmoIndex(ammoId)
                .map(CommonAmmoIndex::getStackSize)
                .orElse(64);
        int perLevel = stackSize * SyncConfig.AMMO_BOX_STACK_SIZE.get();

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

    private static void give(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        if (!p.getInventory().add(s)) p.drop(s, false);
    }
}