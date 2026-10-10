package numunun.bodycamcomponent.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class BodyCamConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue RESPAWN_DELAY;
    public static final ForgeConfigSpec.IntValue START_COUNTDOWN;
    public static final ForgeConfigSpec.BooleanValue CORPSE_ENABLED;
    public static final ForgeConfigSpec.IntValue CORPSE_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ROLE_TITLES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ROLE_KITS;
    public static final ForgeConfigSpec.ConfigValue<String> MEDIC_ROLE;
    public static final ForgeConfigSpec.ConfigValue<String> INTRO_UNIT_LINE;
    public static final ForgeConfigSpec.ConfigValue<String> INTRO_TEAM_FORMAT;
    public static final ForgeConfigSpec.ConfigValue<String> INTRO_FFA_TEXT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> THROWABLE_POOL;
    public static final ForgeConfigSpec.IntValue THROWABLE_COUNT;
    public static final ForgeConfigSpec.BooleanValue DOWNED_ENABLED;
    public static final ForgeConfigSpec.IntValue BLEED_SECONDS;
    public static final ForgeConfigSpec.IntValue REVIVE_SECONDS;
    public static final ForgeConfigSpec.IntValue DOWNED_HEALTH;
    public static final ForgeConfigSpec.IntValue REVIVE_HEALTH;
    public static final ForgeConfigSpec.IntValue LEADER_HEALTH_BONUS;
    public static final ForgeConfigSpec.DoubleValue LEADER_DAMAGE_MULTIPLIER;
    public static final ForgeConfigSpec.ConfigValue<String> OPERATOR_ROLE;
    public static final ForgeConfigSpec.ConfigValue<String> RECON_ROLE;
    public static final ForgeConfigSpec.DoubleValue RECON_SPOT_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PRIMARY_TYPES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SECONDARY_TYPES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPARE_MAGS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MIN_SPARE_AMMO;
    public static final ForgeConfigSpec.BooleanValue RANDOM_ATTACHMENTS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ATTACHMENT_EMPTY_CHANCE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ATTACHMENT_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ROLE_TITLES_EN;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TEAM_NAMES_EN;
    public static final ForgeConfigSpec.BooleanValue HIDE_CHAT_IN_MATCH;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("game");
        RESPAWN_DELAY = b
                .comment("사망 후 리스폰까지 대기 시간(초). 대기 중에는 같은 팀원 시점을 관전합니다. 0이면 즉시 리스폰.")
                .defineInRange("respawnDelaySeconds", 30, 0, 300);
        START_COUNTDOWN = b
                .comment("경기 시작 전 제자리 대기 카운트다운(초). 0이면 바로 시작.")
                .defineInRange("startCountdownSeconds", 5, 0, 30);
        CORPSE_ENABLED = b
                .comment("플레이어가 죽은 자리에 시체를 남길지 여부")
                .define("corpseEnabled", false);
        CORPSE_SECONDS = b
                .comment("시체가 남아 있는 시간(초). 0이면 다음 경기 시작 전까지 유지.")
                .defineInRange("corpseSeconds", 120, 0, 3600);
        ROLE_TITLES = b
                .comment("경기 시작 시 배정되는 직급. 첫 번째 직급은 팀당 한 명 (팀장)")
                .defineList("roleTitles", List.of(
                        "팀장", "작전관", "정찰 지원", "화력 지원", "현장 지원", "근접 경호", "의무 지원"
                ), o -> o instanceof String);
        ROLE_TITLES_EN = b
                .comment("화면 오른쪽 위에 표시할 직급의 영어 이름. 형식: \"직급=영어 이름\"")
                .defineList("roleTitlesEnglish", List.of(
                        "팀장=Team Leader",
                        "작전관=Tactical Operator",
                        "정찰 지원=Recon Support",
                        "화력 지원=Combat Support",
                        "현장 지원=Field Support",
                        "근접 경호=Close Protection",
                        "의무 지원=Medical Support"
                ), o -> o instanceof String);
        TEAM_NAMES_EN = b
                .comment("화면 오른쪽 위에 표시할 팀의 영어 이름. 형식: \"팀 내부 이름=영어 이름\"")
                .defineList("teamNamesEnglish", List.of("blue=Kestrel", "red=Volkov"), o -> o instanceof String);
        ROLE_KITS = b
                .comment("직급별 고정 지급 장비. 형식: \"직급=아이템ID*개수,아이템ID*개수\" (개수 생략 시 1)")
                .defineList("roleKits", List.of(
                        "팀장=minecraft:spyglass",
                        "작전관=superbwarfare:m18_smoke_grenade",
                        "정찰 지원=minecraft:spyglass,superbwarfare:m18_smoke_grenade",
                        "화력 지원=superbwarfare:hand_grenade",
                        "현장 지원=superbwarfare:claymore_mine*2,superbwarfare:crowbar",
                        "근접 경호=superbwarfare:rgo_grenade",
                        "의무 지원=superbwarfare:medical_kit*3"
                ), o -> o instanceof String);
        MEDIC_ROLE = b
                .comment("팀원을 2배 빠르게 치료하는 직급 이름")
                .define("medicRole", "의무 지원");
        LEADER_HEALTH_BONUS = b
                .comment("팀장 추가 체력 (2 = 하트 1칸, 팀전만). 0이면 없음")
                .defineInRange("leaderHealthBonus", 4, 0, 20);
        LEADER_DAMAGE_MULTIPLIER = b
                .comment("팀장이 플레이어에게 주는 피해 배율 (팀전만). 1.0이면 보너스 없음")
                .defineInRange("leaderDamageMultiplier", 1.0, 1.0, 2.0);
        INTRO_UNIT_LINE = b
                .comment("시작 연출 맨 위에 표시할 소속 문구 (가상 지역명)")
                .define("introUnitLine", "드라벤스크 지역 작전부");
        INTRO_TEAM_FORMAT = b
                .comment("시작 연출의 팀 표시 형식. {team}은 팀 표시 이름 (예: \"{team} 분대\")")
                .define("introTeamFormat", "{team}");
        INTRO_FFA_TEXT = b
                .comment("개인전 시작 연출에서 팀 자리에 표시할 문구")
                .define("introFfaText", "독립 계약자");
        HIDE_CHAT_IN_MATCH = b
                .comment("경기 중에는 채팅창을 화면에 띄우지 않음 (T를 눌러 열면 볼 수 있음)")
                .define("hideChatInMatch", true);
        b.pop();

        b.push("loadout");
        PRIMARY_TYPES = b
                .comment("주무기로 뽑을 TaCZ 총기 종류 (rifle, smg, shotgun, sniper, mg, rpg, pistol)")
                .defineList("primaryTypes", List.of("rifle", "smg", "shotgun", "sniper"), o -> o instanceof String);
        SECONDARY_TYPES = b
                .comment("보조무기로 뽑을 TaCZ 총기 종류")
                .defineList("secondaryTypes", List.of("pistol"), o -> o instanceof String);
        GUN_BLACKLIST = b
                .comment("종류와 상관없이 지급하지 않을 총기 ID")
                .defineList("gunBlacklist", List.of("tacz:m134"),
                        o -> o instanceof String s && ResourceLocation.isValidResourceLocation(s));
        SPARE_MAGS = b
                .comment("종류별 예비 탄창 수. 형식: \"종류=개수\" (없는 종류는 4)")
                .defineList("spareMags", List.of("pistol=4", "smg=5", "rifle=5", "sniper=4", "shotgun=6"),
                        o -> o instanceof String);
        MIN_SPARE_AMMO = b
                .comment("종류별 최소 예비 탄약 (탄창이 작은 총 보정). 형식: \"종류=발수\"")
                .defineList("minSpareAmmo", List.of("pistol=40", "sniper=30", "shotgun=60"),
                        o -> o instanceof String);
        RANDOM_ATTACHMENTS = b
                .comment("총마다 장착 가능한 부착물을 랜덤으로 달아줌 (확장 탄창은 항상 1단계). false면 이전 총의 부착물을 옮겨 달아줌")
                .define("randomAttachments", true);
        ATTACHMENT_EMPTY_CHANCE = b
                .comment("슬롯별로 아무것도 안 달 확률 (0~1). 형식: \"슬롯=확률\" (슬롯: scope, muzzle, grip, laser, stock)")
                .defineList("attachmentEmptyChance",
                        List.of("scope=0.2", "muzzle=0.3", "grip=0.3", "laser=0.5", "stock=0.3"),
                        o -> o instanceof String);
        ATTACHMENT_BLACKLIST = b
                .comment("랜덤으로 달지 않을 부착물 ID")
                .defineList("attachmentBlacklist", List.of(),
                        o -> o instanceof String s && ResourceLocation.isValidResourceLocation(s));
        THROWABLE_POOL = b
                .comment("리스폰 시 랜덤 지급할 투척물 아이템 ID 목록")
                .defineList("throwablePool",
                        List.of("superbwarfare:hand_grenade", "superbwarfare:rgo_grenade", "superbwarfare:m18_smoke_grenade"),
                        o -> o instanceof String s && ResourceLocation.isValidResourceLocation(s));
        THROWABLE_COUNT = b
                .comment("지급할 투척물 개수 (목록에서 하나씩 랜덤으로 뽑음)")
                .defineInRange("throwableCount", 2, 0, 9);
        b.pop();

        b.push("downed");
        DOWNED_ENABLED = b
                .comment("치명상을 입으면 즉시 죽지 않고 빈사 상태가 됨 (개인전 제외)")
                .define("enabled", true);
        BLEED_SECONDS = b
                .comment("빈사 상태로 버틸 수 있는 시간(초). 치료받는 동안은 멈춤.")
                .defineInRange("bleedSeconds", 30, 5, 300);
        REVIVE_SECONDS = b
                .comment("팀원이 옆에서 웅크려 살리는 데 걸리는 시간(초)")
                .defineInRange("reviveSeconds", 5, 1, 60);
        DOWNED_HEALTH = b
                .comment("빈사 상태의 체력. 이만큼 더 맞으면 사망 (2 = 하트 1칸)")
                .defineInRange("downedHealth", 6, 1, 20);
        REVIVE_HEALTH = b
                .comment("살아났을 때 체력")
                .defineInRange("reviveHealth", 6, 1, 20);
        b.pop();
        OPERATOR_ROLE = b
                .comment("아군을 벽 너머로도 보고, 핑이 2배 오래 남는 직급")
                .define("operatorRole", "작전관");
        RECON_ROLE = b
                .comment("망원경으로 적을 보면 자동으로 적 핑을 찍는 직급")
                .define("reconRole", "정찰 지원");
        RECON_SPOT_SECONDS = b
                .comment("정찰 지원이 망원경으로 적을 몇 초 봐야 식별되는지")
                .defineInRange("reconSpotSeconds", 1.0, 0.25, 10.0);

        SPEC = b.build();
    }

    private BodyCamConfig() {}
}