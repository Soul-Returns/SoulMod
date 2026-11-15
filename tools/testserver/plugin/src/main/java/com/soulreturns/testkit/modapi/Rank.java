package com.soulreturns.testkit.modapi;

/** `hx rank` names mapped onto net.hypixel.data.rank ids (PlayerRank, PackageRank, MonthlyPackageRank). */
public enum Rank {
    NONE(1, 1, 1, null), VIP(1, 2, 1, null), VIP_PLUS(1, 3, 1, null), MVP(1, 4, 1, null),
    MVP_PLUS(1, 5, 1, null), MVP_PLUS_PLUS(1, 5, 2, null),
    YOUTUBER(2, 5, 1, "§c[§fYOUTUBE§c]"), STAFF(3, 5, 1, "§c[§6ዞ§c]"), ADMIN(4, 5, 1, "§c[ADMIN]");

    final int playerRank;
    final int packageRank;
    final int monthlyRank;
    final String prefix;

    Rank(int playerRank, int packageRank, int monthlyRank, String prefix) {
        this.playerRank = playerRank;
        this.packageRank = packageRank;
        this.monthlyRank = monthlyRank;
        this.prefix = prefix;
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT).replace("_plus", "+");
    }

    public static Rank byName(String key) {
        for (Rank r : values()) if (r.key().equals(key)) return r;
        return NONE;
    }
}
