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
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.util.AttachmentDataUtils;
import java.util.EnumMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import numunun.bodycamcomponent.config.BodyCamConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

public final class LoadoutManager {
    public static final String STASH_KEY = "bodycam_stash";
    public static final String ATTACH_KEY = "bodycam_attachments";
    public static final String AUTO_TAG = "BodyCamAuto";
    public static final String KIT_TAG = "BodyCamKit";

    // 지급 규칙은 설정 파일 [loadout]에서 읽음. "종류=숫자" 목록에서 값 찾기
    private static int lookup(List<? extends String> entries, String key, int fallback) {
        for (String e : entries) {
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(key)) {
                try {
                    return Integer.parseInt(e.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    // ── 랜덤 부착물 ──
    private static final AttachmentType[] RANDOM_SLOTS = {
            AttachmentType.SCOPE, AttachmentType.MUZZLE, AttachmentType.GRIP,
            AttachmentType.LASER, AttachmentType.STOCK
    };

    private static void randomizeAttachments(ItemStack gun, GunData data, RandomSource r) {
        if (!(gun.getItem() instanceof IGun iGun) || iGun.hasAttachmentLock(gun)) return;
        List<? extends String> blacklist = BodyCamConfig.ATTACHMENT_BLACKLIST.get();

        // 이 총에 달 수 있는 부착물을 슬롯별로 모으기
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

        // 슬롯마다 확률적으로 비우거나 랜덤 장착
        for (AttachmentType type : RANDOM_SLOTS) {
            List<ResourceLocation> list = byType.get(type);
            if (list == null || list.isEmpty()) continue;
            if (r.nextFloat() < emptyChance(type)) continue;
            install(iGun, gun, list.get(r.nextInt(list.size())));
        }
        if (!mags.isEmpty()) install(iGun, gun, mags.get(r.nextInt(mags.size())));

        // 확장 탄창 용량까지 꽉 채우기
        iGun.setCurrentAmmoCount(gun, AttachmentDataUtils.getAmmoCountWithAttachment(gun, data));
    }

    private static void install(IGun iGun, ItemStack gun, ResourceLocation id) {
        iGun.installAttachment(gun, AttachmentItemBuilder.create().setId(id).build());
    }

    private static float emptyChance(AttachmentType type) {
        String key = type.name().toLowerCase();
        for (String e : BodyCamConfig.ATTACHMENT_EMPTY_CHANCE.get()) {
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).trim().equals(key)) {
                try {
                    return Float.parseFloat(e.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0f;
    }

    private static final ResourceLocation AMMO_BOX_ID = new ResourceLocation("tacz", "ammo_box");
    private static final int MAX_BOX_LEVEL = 2;  // 0 철, 1 금, 2 다이아

    private static boolean enabled = false;

    private LoadoutManager() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }

    // 사망 시 사라지는 대상품: 총, 탄약, 탄약 상자, 투척물, 직급 장비
    public static boolean isTarget(ItemStack s) {
        return s.getItem() instanceof IGun
                || s.getItem() instanceof IAmmo
                || s.getItem() instanceof IAmmoBox
                || isThrowable(s)
                || (s.hasTag() && s.getTag().getBoolean(KIT_TAG));
    }

    private static boolean isThrowable(ItemStack s) {
        if (s.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(s.getItem());
        return key != null && BodyCamConfig.THROWABLE_POOL.get().contains(key.toString());
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
                if (!BodyCamConfig.RANDOM_ATTACHMENTS.get()) attachments.addAll(extractAttachments(s));
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

        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {            if (BodyCamConfig.GUN_BLACKLIST.get().contains(entry.getKey().toString())) continue;
            String type = entry.getValue().getType();
            if (BodyCamConfig.PRIMARY_TYPES.get().contains(type)) primaries.add(entry);
            else if (BodyCamConfig.SECONDARY_TYPES.get().contains(type)) secondaries.add(entry);
        }

        List<ItemStack> guns = new ArrayList<>();
        RandomSource r = p.getRandom();
        if (!primaries.isEmpty()) guns.add(giveGun(p, primaries.get(r.nextInt(primaries.size())), 0));
        if (!secondaries.isEmpty()) guns.add(giveGun(p, secondaries.get(r.nextInt(secondaries.size())), 1));
        giveThrowables(p);
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

        if (BodyCamConfig.RANDOM_ATTACHMENTS.get()) randomizeAttachments(gun, data, p.getRandom());

        Inventory inv = p.getInventory();
        if (inv.getItem(slot).isEmpty()) inv.setItem(slot, gun);
        else giveHotbar(p, gun);

        int mags = lookup(BodyCamConfig.SPARE_MAGS.get(), type, 4);
        int total = Math.max(data.getAmmoAmount() * mags, lookup(BodyCamConfig.MIN_SPARE_AMMO.get(), type, 0));
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

    // 인벤토리 안쪽(10~36번 칸) 우선, 없으면 아무 곳, 그래도 없으면 바닥
    private static void give(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 9; i < 36 && !s.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur.isEmpty()) {
                inv.setItem(i, s);
                return;
            }
            if (ItemStack.isSameItemSameTags(cur, s) && cur.getCount() < cur.getMaxStackSize()) {
                int move = Math.min(s.getCount(), cur.getMaxStackSize() - cur.getCount());
                cur.grow(move);
                s.shrink(move);
            }
        }
        if (!s.isEmpty() && !inv.add(s)) p.drop(s, false);
    }

    // 핫바 앞쪽부터: 같은 아이템이 있으면 겹치고, 없으면 첫 빈칸
    private static void giveHotbar(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9 && !s.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameTags(cur, s) && cur.getCount() < cur.getMaxStackSize()) {
                int move = Math.min(s.getCount(), cur.getMaxStackSize() - cur.getCount());
                cur.grow(move);
                s.shrink(move);
            }
        }
        for (int i = 0; i < 9 && !s.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, s);
                return;
            }
        }
        give(p, s);  // 핫바가 꽉 찼으면 인벤토리로
    }


    // ── 직급별 고정 장비 ──
    public static void giveRoleKit(ServerPlayer p, @Nullable String role) {
        if (role == null || role.isEmpty()) return;
        for (String line : BodyCamConfig.ROLE_KITS.get()) {
            int eq = line.indexOf('=');
            if (eq < 0 || !line.substring(0, eq).trim().equals(role)) continue;

            for (String part : line.substring(eq + 1).split(",")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                int count = 1;
                int star = s.indexOf('*');
                if (star >= 0) {
                    try {
                        count = Integer.parseInt(s.substring(star + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    s = s.substring(0, star).trim();
                }
                ResourceLocation id = ResourceLocation.tryParse(s);
                Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                if (item == null || item == Items.AIR) continue;  // 없는 모드 아이템은 건너뜀

                int max = new ItemStack(item).getMaxStackSize();
                while (count > 0) {
                    int n = Math.min(count, max);
                    ItemStack stack = new ItemStack(item, n);
                    stack.getOrCreateTag().putBoolean(KIT_TAG, true);
                    giveHotbar(p, stack);
                    count -= n;
                }
            }
        }
    }

    // ── 투척물 랜덤 지급 ──
    private static void giveThrowables(ServerPlayer p) {
        List<Item> pool = new ArrayList<>();
        for (String id : BodyCamConfig.THROWABLE_POOL.get()) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null && item != Items.AIR) pool.add(item);
        }
        if (pool.isEmpty()) return;

        RandomSource r = p.getRandom();
        int count = BodyCamConfig.THROWABLE_COUNT.get();
        for (int i = 0; i < count; i++) {
            giveHotbar(p, new ItemStack(pool.get(r.nextInt(pool.size()))));
        }
    }
}