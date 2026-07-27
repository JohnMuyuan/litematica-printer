package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum WorkAreaSourceType implements ConfigOptionListEntry<WorkAreaSourceType> {
    FIXED_LITEMATICA("workAreaSource.fixedLitematica"),
    FOLLOW_PLAYER("workAreaSource.followPlayer");

    private final I18n i18n;

    WorkAreaSourceType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return this.i18n;
    }
}
