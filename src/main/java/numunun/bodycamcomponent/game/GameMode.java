package numunun.bodycamcomponent.game;

public enum GameMode {
    FFA("ffa", "개인전"),
    TDM("tdm", "팀 데스매치");

    public final String id;
    public final String label;

    GameMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static GameMode byId(String id) {
        for (GameMode m : values()) if (m.id.equals(id)) return m;
        return TDM;
    }
}