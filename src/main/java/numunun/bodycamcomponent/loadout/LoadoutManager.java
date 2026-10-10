package numunun.bodycamcomponent.loadout;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.api.entity.IGunOperator;
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
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.util.BodyCamLog;
import numunun.bodycamcomponent.util.Safe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 장비 지급의 중심: 랜덤 총기, 투척물, 직급 장비, 사망 시 보관과 리스폰 시 반환.
// 아이템 넣기는 LoadoutItems, 부착물은 LoadoutAttachments가 담당
public final class LoadoutManager {
    public static final String STASH_KEY = "bodycam_stash";
    public static final String ATTACH_KEY = "bodycam_attachments";
    public static final String KIT_TAG = "BodyCamKit";

    private static boolean enabled = false;

    // 지급 후보 총 목록 (경기 시작마다 새로 만듦)
    private static List<Map.Entry<ResourceLocation, CommonGunIndex>> primaryPool;
    private static List<Map.Entry<ResourceLocation, CommonGunIndex>> secondaryPool;

    private LoadoutManager() {}

    // 경기 시작 시 호출: 총기 목록과 부착물 호환 목록을 새로 만들게 함
    public static void clearCaches() {
        primaryPool = null;
        secondaryPool = null;
        LoadoutAttachments.clearCache();
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) { enabled = value; }

    // ── 분류 ──
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

    // ── 대상품 제거 (부착물 옮겨 달기 모드면 장착 부착물 회수) ──
    public static List<ItemStack> clearTargets(ServerPlayer p) {
        Safe.run("총 상태 초기화", () -> IGunOperator.fromLivingEntity(p).initialData());
        Inventory inv = p.getInventory();
        List<ItemStack> attachments = new ArrayList<>();
        boolean carryOver = !BodyCamConfig.RANDOM_ATTACHMENTS.get();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isTarget(s)) {
                if (carryOver) attachments.addAll(LoadoutAttachments.extract(s));
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return attachments;
    }

    // 총을 바꿔 끼운 뒤 TaCZ에 "새 총을 꺼냈다"고 알림.
    // 이전 총의 재장전, 노리쇠 상태가 새 총에 이어지면 총기 스크립트가 오류를 내고,
    // 새 총의 부착물 효과도 이때 다시 계산됨
    public static void refreshHeldGun(ServerPlayer p) {
        Safe.run("새 총 꺼내기", () -> IGunOperator.fromLivingEntity(p).draw(p::getMainHandItem));
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
                    LoadoutItems.give(p, s);
                }
            }
            data.remove(STASH_KEY);
        }

        if (data.contains(ATTACH_KEY, Tag.TAG_LIST)) {
            ListTag list = data.getList(ATTACH_KEY, Tag.TAG_COMPOUND);
            List<ItemStack> attachments = new ArrayList<>();
            for (int k = 0; k < list.size(); k++) attachments.add(ItemStack.of(list.getCompound(k)));
            LoadoutAttachments.installOrGive(p, newGuns, attachments);
            data.remove(ATTACH_KEY);
        }
        LoadoutAttachments.autoInstallFromInventory(p, newGuns);
    }

    // ── 부착물 (외부 창구) ──
    public static boolean isAutoMarked(ItemStack s) {
        return LoadoutAttachments.isAutoMarked(s);
    }

    public static void installOrGive(ServerPlayer p, List<ItemStack> guns, List<ItemStack> attachments) {
        LoadoutAttachments.installOrGive(p, guns, attachments);
    }

    public static void autoInstallFromInventory(ServerPlayer p, List<ItemStack> guns) {
        LoadoutAttachments.autoInstallFromInventory(p, guns);
    }

    // ── 랜덤 지급 (지급한 총 목록 반환) ──
    public static List<ItemStack> giveRandomLoadout(ServerPlayer p) {
        if (primaryPool == null || secondaryPool == null) buildGunPools();

        List<ItemStack> guns = new ArrayList<>();
        ItemStack primary = giveFromPool(p, primaryPool, 0);
        if (!primary.isEmpty()) guns.add(primary);
        ItemStack secondary = giveFromPool(p, secondaryPool, 1);
        if (!secondary.isEmpty()) guns.add(secondary);
        Safe.run("투척물 지급", () -> giveThrowables(p));
        return guns;
    }

    private static void buildGunPools() {
        List<Map.Entry<ResourceLocation, CommonGunIndex>> primaries = new ArrayList<>();
        List<Map.Entry<ResourceLocation, CommonGunIndex>> secondaries = new ArrayList<>();
        List<? extends String> blacklist = BodyCamConfig.GUN_BLACKLIST.get();
        List<? extends String> primaryTypes = BodyCamConfig.PRIMARY_TYPES.get();
        List<? extends String> secondaryTypes = BodyCamConfig.SECONDARY_TYPES.get();

        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {
            if (blacklist.contains(entry.getKey().toString())) continue;
            String type = entry.getValue().getType();
            if (primaryTypes.contains(type)) primaries.add(entry);
            else if (secondaryTypes.contains(type)) secondaries.add(entry);
        }
        primaryPool = primaries;
        secondaryPool = secondaries;
        BodyCamLog.info("지급 후보 총기: 주무기 " + primaries.size() + "정, 보조무기 " + secondaries.size() + "정");
        if (primaries.isEmpty()) BodyCamLog.warnOnce("no-primary", "주무기 후보가 없습니다. primaryTypes, gunBlacklist 설정을 확인하세요.");
        if (secondaries.isEmpty()) BodyCamLog.warnOnce("no-secondary", "보조무기 후보가 없습니다. secondaryTypes 설정을 확인하세요.");
    }

    // 고른 총의 데이터가 잘못돼 있으면 다른 총으로 최대 3번 다시 시도
    private static ItemStack giveFromPool(ServerPlayer p, List<Map.Entry<ResourceLocation, CommonGunIndex>> pool, int slot) {
        if (pool.isEmpty()) return ItemStack.EMPTY;
        RandomSource r = p.getRandom();
        for (int attempt = 0; attempt < 3; attempt++) {
            Map.Entry<ResourceLocation, CommonGunIndex> entry = pool.get(r.nextInt(pool.size()));
            try {
                return giveGun(p, entry, slot);
            } catch (Exception e) {
                BodyCamLog.error("총 지급: " + entry.getKey(), e);
            }
        }
        return ItemStack.EMPTY;
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

        if (BodyCamConfig.RANDOM_ATTACHMENTS.get()) {
            Safe.run("랜덤 부착물: " + id, () -> LoadoutAttachments.randomize(gun, data, p.getRandom()));
        }

        Inventory inv = p.getInventory();
        if (inv.getItem(slot).isEmpty()) inv.setItem(slot, gun);
        else LoadoutItems.giveHotbar(p, gun);

        int mags = LoadoutItems.intValue(BodyCamConfig.SPARE_MAGS.get(), type, 4);
        int minAmmo = LoadoutItems.intValue(BodyCamConfig.MIN_SPARE_AMMO.get(), type, 0);
        LoadoutItems.giveAmmoBoxes(p, data.getAmmoId(), Math.max(data.getAmmoAmount() * mags, minAmmo));
        return gun;
    }

    // ── 투척물 랜덤 지급 ──
    private static void giveThrowables(ServerPlayer p) {
        List<Item> pool = new ArrayList<>();
        for (String id : BodyCamConfig.THROWABLE_POOL.get()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
            if (item != null && item != Items.AIR) pool.add(item);
            else BodyCamLog.warnOnce("throwable:" + id, "투척물 아이템을 찾을 수 없습니다: " + id + " (모드가 없거나 ID 오타)");
        }
        if (pool.isEmpty()) return;

        RandomSource r = p.getRandom();
        int count = BodyCamConfig.THROWABLE_COUNT.get();
        for (int i = 0; i < count; i++) {
            LoadoutItems.giveHotbar(p, new ItemStack(pool.get(r.nextInt(pool.size()))));
        }
    }

    // ── 직급별 고정 장비 (형식: "직급=아이템ID*개수,아이템ID*개수") ──
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
                if (item == null || item == Items.AIR) {  // 없는 모드 아이템은 건너뜀
                    BodyCamLog.warnOnce("kit:" + s, "직급 장비 아이템을 찾을 수 없습니다: " + s + " (모드가 없거나 ID 오타)");
                    continue;
                }

                int max = new ItemStack(item).getMaxStackSize();
                while (count > 0) {
                    int n = Math.min(count, max);
                    ItemStack stack = new ItemStack(item, n);
                    stack.getOrCreateTag().putBoolean(KIT_TAG, true);
                    LoadoutItems.giveHotbar(p, stack);
                    count -= n;
                }
            }
        }
    }
}