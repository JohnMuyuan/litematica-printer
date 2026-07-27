package me.aleksilassila.litematica.printer.gui;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.mixin_extension.ConfigExtension;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.network.RemoteWorkPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public class ConfigUi extends GuiConfigsBase {
    private static final String ATTRIBUTION_KEY = "litematica-printer.config.attribution";
    private static final String ATTRIBUTION_FALLBACK =
            "Free on GitHub - paid? Refund now! Author: YP.MK";
    private static Tab tab = Tab.CORE;

    public ConfigUi(@Nullable Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, getConfigTitle());
        this.useTitleHierarchy = false;
    }

    private static String getConfigTitle() {
        String attribution = Component.translatableWithFallback(
                ATTRIBUTION_KEY,
                ATTRIBUTION_FALLBACK
        ).getString();
        return Reference.MOD_NAME + " | " + attribution;
    }

    public ConfigUi() {
        //#if MC > 260100
        //$$ this(Minecraft.getInstance().gui.screen());
        //#else
        this(Minecraft.getInstance().screen);
        //#endif
    }

    public static void refresh() {
        //#if MC > 260100
        //$$ if (Reference.MINECRAFT.gui.screen() instanceof ConfigUi gui) {
        //#else
        if (Reference.MINECRAFT.screen instanceof ConfigUi gui) {
        //#endif
            gui.initGui();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        int x = 10;
        int y = 26;
        for (Tab tab : Tab.values()) {
            x += this.createButton(x, y, -1, tab);
        }
    }

    public void reset() {
        reCreateListWidget();
        Objects.requireNonNull(getListWidget()).resetScrollbarPosition();
        initGui();
    }

    private int createButton(int x, int y, int width, Tab tab) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getName(), tab.getComment());
        button.setEnabled(ConfigUi.tab != tab);
        this.addButton(button, new ButtonListener(tab, this));
        return button.getWidth() + 2;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        ImmutableList.Builder<ConfigOptionWrapper> builder = ImmutableList.builder();
        for (IConfigBase config : ConfigUi.tab.getConfigs()) {
            if (config instanceof ConfigExtension extension) {
                @Nullable BooleanSupplier visible = extension.litematica_printer$getVisible();
                if (visible != null && visible.getAsBoolean()) {
                    builder.add(new ConfigOptionWrapper(config));
                }
            }
        }
        return builder.build();
    }

    public enum Tab {
        ALL(I18n.of("category.all")),
        CORE(I18n.of("category.core")),
        SPECIAL(I18n.of("category.special")),
        PLACEMENT(I18n.of("category.placement")),
        BREAK(I18n.of("category.break")),
        HOTKEYS(I18n.of("category.hotkeys")),
        PRINT(I18n.of("category.print")),
        EXCAVATE(I18n.of("category.mine")),
        MAGNET(I18n.of("category.magnet")),
        CLEAR(I18n.of("category.clear")),
        BEDROCK(I18n.of("category.bedrock")),
        FILL(I18n.of("category.fill")),
        FLUID(I18n.of("category.fluid"));

        private final I18n i18n;

        Tab(I18n i18n) {
            this.i18n = i18n;
        }

        public String getName() {
            String name = i18n.getConfigName().getString();
            return this.requiresServer() && RemoteWorkPacket.isMissingOnRemoteServer()
                    ? "\u00a7e" + name + "\u00a7r"
                    : name;
        }

        public String getComment() {
            String comment = i18n.getConfigDesc().getString();
            return this.requiresServer() && RemoteWorkPacket.isMissingOnRemoteServer()
                    ? comment + "\n\u00a7e" + RemoteWorkPacket.MISSING_SERVER_WARNING + "\u00a7r"
                    : comment;
        }

        private boolean requiresServer() {
            return switch (this) {
                case PRINT, EXCAVATE, MAGNET, CLEAR, BEDROCK, FILL, FLUID -> true;
                default -> false;
            };
        }

        public ImmutableList<IConfigBase> getConfigs() {
            return switch (this) {
                case ALL -> Configs.OPTIONS;
                case CORE -> Configs.Core.OPTIONS;
                case SPECIAL -> Configs.Special.OPTIONS;
                case PLACEMENT -> Configs.Placement.OPTIONS;
                case BREAK -> Configs.Break.OPTIONS;
                case PRINT -> Configs.Print.OPTIONS;
                case EXCAVATE -> Configs.Mine.OPTIONS;
                case MAGNET -> Configs.Magnet.OPTIONS;
                case CLEAR -> Configs.Clear.OPTIONS;
                case BEDROCK -> Configs.Bedrock.OPTIONS;
                case FILL -> Configs.Fill.OPTIONS;
                case FLUID -> Configs.Fluid.OPTIONS;
                case HOTKEYS -> Configs.Hotkeys.OPTIONS;
            };
        }
    }

    public record ButtonListener(Tab tab, ConfigUi parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            ConfigUi.tab = this.tab;
            this.parent.reset();
        }
    }
}
