package me.ryanhamshire.GriefPrevention.enums;

public enum PistonMode {

    EVERYWHERE,
    EVERYWHERE_SIMPLE,
    CLAIMS_ONLY,
    IGNORED;

    public static PistonMode of(String value) {
        if (value == null) {
            return CLAIMS_ONLY;
        }
        try {
            return valueOf(value.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return CLAIMS_ONLY;
        }
    }

}
