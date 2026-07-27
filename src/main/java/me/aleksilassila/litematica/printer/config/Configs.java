package me.aleksilassila.litematica.printer.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.*;
import fi.dy.masa.malilib.config.options.*;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import fi.dy.masa.malilib.config.ConfigManager;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

public class Configs extends ConfigBuilders implements IConfigHandler {
    private static final Configs INSTANCE = new Configs();

    private static final String FILE_PATH = "./config/" + Reference.MOD_ID + ".json";
    private static final File CONFIG_DIR = new File("./config");

    private static final KeybindSettings GUI_NO_ORDER = KeybindSettings.create(KeybindSettings.Context.GUI, KeyAction.PRESS, false, false, false, true);

    // 閰嶇疆椤甸潰鏄惁鍙(鍑芥暟寮? 鍔ㄦ€佽幏鍙? 鍏ㄥ眬缁熶竴浣跨�?
    private static final BooleanSupplier isSingle = () -> Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.SINGLE);
    private static final BooleanSupplier isMulti = () -> Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI);
    private static final BooleanSupplier isFixedWorkArea = () -> Core.WORK_AREA_SOURCE.getOptionListValue().equals(WorkAreaSourceType.FIXED_LITEMATICA);

    private static final BooleanSupplier isBreakCustom = () -> Break.BREAK_LIMITER.getOptionListValue().equals(ExcavateListMode.CUSTOM);
    private static final BooleanSupplier isBreakWhitelist = () -> isBreakCustom.getAsBoolean() && Break.BREAK_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isBreakBlacklist = () -> isBreakCustom.getAsBoolean() && Break.BREAK_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);


    private static final BooleanSupplier isExcavateCustom = () -> Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.CUSTOM);
    private static final BooleanSupplier isExcavateWhitelist = () -> isExcavateCustom.getAsBoolean() && Mine.EXCAVATE_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isExcavateBlacklist = () -> isExcavateCustom.getAsBoolean() && Mine.EXCAVATE_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);
    private static final BooleanSupplier isPickupFilterEnabled = () -> Magnet.ENABLED.getBooleanValue() && Magnet.FILTER_ENABLED.getBooleanValue();
    private static final BooleanSupplier isPickupCustomFilter = () -> isPickupFilterEnabled.getAsBoolean() && Magnet.FILTER_MODE.getOptionListValue().equals(PickupFilterMode.CUSTOM);
    private static final BooleanSupplier isPickupCustomWhitelist = () -> isPickupCustomFilter.getAsBoolean() && Magnet.CUSTOM_LIST_TYPE.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isPickupCustomBlacklist = () -> isPickupCustomFilter.getAsBoolean() && Magnet.CUSTOM_LIST_TYPE.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);
    private static final BooleanSupplier isBlocklist = () -> Fill.FILL_BLOCK_MODE.getOptionListValue().equals(FillBlockModeType.BLOCKLIST);


    public static final ImmutableList<IConfigBase> OPTIONS;
    public static final ImmutableList<IHotkey> HOTKEYS;


    static {
        LinkedHashSet<IConfigBase> optionSet = new LinkedHashSet<>();
        optionSet.addAll(Core.OPTIONS);           // 鏍稿�?
        optionSet.addAll(Special.OPTIONS);        // 鐗规�?
        optionSet.addAll(Placement.OPTIONS);      // 鏀剧疆
        optionSet.addAll(Break.OPTIONS);          // 鐮村�?
        optionSet.addAll(Hotkeys.OPTIONS);        // 鐑�?
        optionSet.addAll(Print.OPTIONS);          // 鎵撳�?
        optionSet.addAll(Mine.OPTIONS);           // 鎸栨�?
        optionSet.addAll(Magnet.OPTIONS);
        optionSet.addAll(Clear.OPTIONS);          // 娓呴�?
        optionSet.addAll(Fill.OPTIONS);           // 濉�?
        optionSet.addAll(Fluid.OPTIONS);          // 鎺掓祦浣?
        optionSet.addAll(Bedrock.OPTIONS);        // 鐮村熀宀?
        OPTIONS = ImmutableList.copyOf(optionSet);

        List<IHotkey> hotkeys = new ArrayList<>();
        for (IConfigBase option : optionSet) {
            if (option instanceof IHotkey hokey) {
                hotkeys.add(hokey);
            }
        }
        HOTKEYS = ImmutableList.copyOf(hotkeys);
    }

    public static class Core {
        // 鎵撳嵃鐘舵€?
        public static final ConfigBooleanHotkeyed WORK_SWITCH = booleanHotkey("workingSwitch")
                .defaultValue(false)
                .defaultHotkey("CAPS_LOCK")
                .keybindSettings(KeybindSettings.PRESS_ALLOWEXTRA_EMPTY)
                .build();

        // 鏍稿�?- 妯″紡鍒囨�?
        public static final ConfigOptionList WORK_MODE = optionList("modeSwitch")
                .defaultValue(WorkingModeType.SINGLE)
                .build();

        // 澶氭ā - 鎵撳�?
        public static final ConfigBooleanHotkeyed PRINT = booleanHotkey("print")
                .defaultValue(false)
                .setVisible(isMulti) // 浠呭妯″紡鏃舵樉绀?
                .build();

        // 澶氭ā - 鎸栨�?
        public static final ConfigBooleanHotkeyed MINE = booleanHotkey("mine")
                .defaultValue(false)
                .setVisible(isMulti) // 浠呭妯″紡鏃舵樉绀?
                .build();

        // 澶氭ā - 濉�?
        public static final ConfigBooleanHotkeyed FILL = booleanHotkey("fill")
                .defaultValue(false)
                .setVisible(isMulti) // 浠呭妯″紡鏃舵樉绀?
                .build();

        // 澶氭ā - 鎺掓祦浣?
        public static final ConfigBooleanHotkeyed FLUID = booleanHotkey("fluid")
                .defaultValue(false)
                .setVisible(isMulti) // 浠呭妯″紡鏃舵樉绀?
                .build();

        // 鏍稿�?- 鍗曟ā妯″紡
        public static final ConfigOptionList WORK_MODE_TYPE = optionList("printerMode")
                .defaultValue(PrintModeType.PRINTER)
                .setVisible(isSingle) // 浠呭崟妯″紡鏃舵樉绀?
                .build();

        public static final ConfigOptionList WORK_AREA_SOURCE = optionList("workAreaSource")
                .defaultValue(WorkAreaSourceType.FIXED_LITEMATICA)
                .build();

        // 鏍稿�?- 宸ヤ綔鍗婂緞
        public static final ConfigInteger WORK_RANGE = integer("workRange")
                .defaultValue(6)
                .range(1, 256)
                .build();

        public static final ConfigInteger SCAN_TIME_BUDGET_MS = integer("scanTimeBudgetMs")
                .defaultValue(2)
                .range(1, 10)
                .build();

        public static final ConfigInteger LAZY_ENTER_TICKS = integer("lazyEnterTicks")
                .defaultValue(10)
                .range(0, 40)
                .build();

        // 鏍稿�?- 妫€鏌ョ帺瀹舵柟鍧椾氦浜掕寖鍥?
        public static final ConfigBoolean CHECK_PLAYER_INTERACTION_RANGE = bool("checkPlayerInteractionRange")
                .defaultValue(true)
                .build();

        // 鏍稿�?- 寤惰繜妫€娴?
        public static final ConfigBoolean LAG_CHECK = bool("printerLagCheck")
                .defaultValue(false)
                .build();

        public static final ConfigInteger LAG_CHECK_MAX = integer("printerLagCheckMax")
                .defaultValue(20)
                .setVisible(LAG_CHECK::getBooleanValue)
                .range(20, 1200)
                .build();

        // 鏍稿�?- 杩唬鍖哄煙褰㈢�?
        public static final ConfigOptionList ITERATOR_SHAPE = optionList("printerIteratorShape")
                .defaultValue(RadiusShapeType.SPHERE)
                .build();

        // 鏍稿�?- 鏄剧ず鎵撳嵃鏈篐UD
        public static final ConfigBoolean RENDER_HUD = bool("renderHud")
                .defaultValue(false)
                .build();

        public static final ConfigInteger RENDER_HUD_X = integer("renderHudX")
                .defaultValue(10)
                .range(0, 4096)
                .setVisible(RENDER_HUD::getBooleanValue)
                .build();

        public static final ConfigInteger RENDER_HUD_Y = integer("renderHudY")
                .defaultValue(10)
                .range(0, 4096)
                .setVisible(RENDER_HUD::getBooleanValue)
                .build();

        public static final ConfigInteger RENDER_HUD_SCALE = integer("renderHudScale")
                .defaultValue(100)
                .range(50, 200)
                .setVisible(RENDER_HUD::getBooleanValue)
                .build();

        // 鏍稿�?- 鑷姩绂佺敤鎵撳嵃鏈?
        public static final ConfigBoolean AUTO_DISABLE_PRINTER = bool("printerAutoDisable")
                .defaultValue(true)
                .build();

        // 閫氱敤閰嶇疆椤瑰垪琛紙鎸夊姛鑳藉垎绫绘帓搴忥級
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                WORK_SWITCH,
                WORK_MODE,
                WORK_MODE_TYPE,
                PRINT,
                MINE,
                FILL,
                FLUID,
                WORK_AREA_SOURCE,
                ITERATOR_SHAPE,
                WORK_RANGE,
                SCAN_TIME_BUDGET_MS,
                LAZY_ENTER_TICKS,
                RENDER_HUD,
                RENDER_HUD_X,
                RENDER_HUD_Y,
                RENDER_HUD_SCALE,
                LAG_CHECK,
                LAG_CHECK_MAX,
                CHECK_PLAYER_INTERACTION_RANGE,
                AUTO_DISABLE_PRINTER
        );
    }

    public static class Special {
        // 淇℃爣鏁堟灉闄愬埗缁曡繃
        public static final ConfigBoolean UNLOCK_BEACON_EFFECTS = bool("unlockBeaconEffects")
                .defaultValue(false)
                .build();

        // Tweakeroo - 鏀惧鍑┖鏀剧疆闄愬�?
        public static final ConfigBoolean TWEAKEROO_ANGEL_BLOCK_MAY_BUILD = bool("tweakerooAngelBlockMayBuild")
                .defaultValue(false)
                .setVisible(ModLoadUtils::isTweakerooLoaded)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                UNLOCK_BEACON_EFFECTS,
                TWEAKEROO_ANGEL_BLOCK_MAY_BUILD
        );
    }

    public static class Placement {

        // 鏍稿�?- 宸ヤ綔闂撮殧
        public static final ConfigInteger PLACE_INTERVAL = integer("placeInterval")
                .defaultValue(0)
                .range(0, 20)
                .build();

        // 姣忓埢鏀剧疆鏂瑰潡鏁?
        public static final ConfigInteger PLACE_BLOCKS_PER_TICK = integer("placeBlocksPerTick")
                .defaultValue(1)
                .range(0, 256)
                .build();

        // 鏀剧疆鍐峰�?
        public static final ConfigInteger PLACE_COOLDOWN = integer("placeCooldown")
                .defaultValue(8)
                .range(0, 64)
                .build();

        // 鍚屾椂绛夊緟鏈嶅姟绔‘璁ょ殑鏀剧疆鏁伴噺銆備笉鍚屼綅缃彲骞惰锛屽悓涓€浣嶇疆濮嬬粓鍗曢銆?
        public static final ConfigInteger PLACE_CONFIRM_WINDOW = integer("placeConfirmWindow")
                .defaultValue(2)
                .range(1, 8)
                .build();

        // 鏈嶅姟绔湭杩斿洖鐩爣鏂瑰潡鐘舵€佹椂淇濈暀 pending 鐨勬渶闀挎椂闂淬�?
        public static final ConfigInteger PLACE_CONFIRM_TIMEOUT = integer("placeConfirmTimeout")
                .defaultValue(80)
                .range(20, 400)
                .build();

        // RTT 鑷€傚簲閲嶆斁闂撮�?- 寮€�?
        // 鏍规嵁鐜╁ ping 鑷姩鎶婃斁缃棿闅旀姮鍒颁笉浣庝簬涓€娆″線�?鍑忓皯鏈嶅姟鍣ㄤ笅銆屽彂鍖呭揩浜庢湇鍔＄纭銆嶅鑷寸殑鏀鹃敊�?
        public static final ConfigBoolean RTT_ADAPTIVE_INTERVAL = bool("placeRttAdaptiveInterval")
                .defaultValue(false)
                .build();

        // RTT 鑷€傚�?- 瀹夊叏绯绘暟(鐧惧垎姣?:�?RTT 鐨勮鐧惧垎姣斾綔涓烘渶灏忛棿闅?100 = 鎭板ソ涓€涓線杩斻�?
        public static final ConfigInteger RTT_SAFETY_PERCENT = integer("placeRttSafetyPercent")
                .defaultValue(100)
                .range(25, 300)
                .setVisible(RTT_ADAPTIVE_INTERVAL::getBooleanValue)
                .build();

        // 涓嬭惤鏂瑰潡妫€�?
        public static final ConfigBoolean FALLING_CHECK = bool("printFallingBlockCheck")
                .defaultValue(true)
                .build();

        // 蹇嵎娼滃奖�?- 寮€�?
        public static final ConfigBoolean QUICK_SHULKER = bool("quickShulker")
                .defaultValue(false)
                .build();

        // 蹇嵎娼滃奖�?- 宸ヤ綔妯″紡
        public static final ConfigOptionList QUICK_SHULKER_MODE = optionList("quickShulkerMode")
                .defaultValue(QuickShulkerModeType.INVOKE)
                .build();

        // 蹇嵎娼滃奖�?- 鍐峰嵈鏃堕棿
        public static final ConfigInteger QUICK_SHULKER_COOLDOWN = integer("quickShulkerCooldown")
                .defaultValue(1)
                .range(0, 20)
                .build();

        // 鍌ㄥ瓨绠＄悊 - 鏈夊簭瀛樻�?
        public static final ConfigBoolean STORE_ORDERLY = bool("storeOrderly")
                .defaultValue(false)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PLACE_INTERVAL,
                PLACE_BLOCKS_PER_TICK,
                PLACE_COOLDOWN,
                PLACE_CONFIRM_WINDOW,
                PLACE_CONFIRM_TIMEOUT,
                RTT_ADAPTIVE_INTERVAL,
                RTT_SAFETY_PERCENT,
                FALLING_CHECK,
                STORE_ORDERLY,
                QUICK_SHULKER,
                QUICK_SHULKER_MODE,
                QUICK_SHULKER_COOLDOWN
        );
    }

    public static class Break {
        public static final ConfigBoolean BREAK_USE_DELAYED_DESTROY = bool("breakUseDelayedDestroy")
                .defaultValue(false)
                .build();

        public static final ConfigInteger BREAK_PROGRESS_THRESHOLD = integer("breakProgressThreshold")
                .defaultValue(100)
                .range(70, 100)
                .build();

        public static final ConfigInteger BREAK_INTERVAL = integer("breakInterval")
                .defaultValue(0)
                .range(0, 20)
                .build();

        public static final ConfigInteger BREAK_BLOCKS_PER_TICK = integer("breakBlocksPerTick")
                .defaultValue(20)
                .range(0, 20)
                .build();

        public static final ConfigInteger BREAK_COOLDOWN = integer("breakCooldown")
                .defaultValue(8)
                .range(0, 64)
                .build();

        public static final ConfigBoolean BREAK_CHECK_HARDNESS = bool("breakCheckHardness")
                .defaultValue(true)
                .build();

        public static final ConfigBoolean BREAK_AUTO_TOOL = bool("breakAutoTool")
                .defaultValue(false)
                .build();

        // 妯″紡闄愬埗鍣?
        public static final ConfigOptionList BREAK_LIMITER = optionList("breakLimiter")
                .defaultValue(ExcavateListMode.CUSTOM)
                .build();

        // 妯″紡闄愬�?
        public static final ConfigOptionList BREAK_LIMIT = optionList("breakLimit")
                .defaultValue(UsageRestriction.ListType.NONE)
                .setVisible(isBreakCustom)
                .build();

        // 鐧藉悕鍗?
        public static final ConfigStringList BREAK_WHITELIST = stringList("breakWhitelist")
                .setVisible(isBreakWhitelist)
                .build();

        // 榛戝悕鍗?
        public static final ConfigStringList BREAK_BLACKLIST = stringList("breakBlacklist")
                .setVisible(isBreakBlacklist)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                BREAK_INTERVAL,
                BREAK_BLOCKS_PER_TICK,
                BREAK_CHECK_HARDNESS,
                BREAK_AUTO_TOOL,
                BREAK_USE_DELAYED_DESTROY,
                BREAK_COOLDOWN,
                BREAK_PROGRESS_THRESHOLD,
                // 闄愬埗鍣?
                BREAK_LIMITER,
                BREAK_LIMIT,
                BREAK_WHITELIST,
                BREAK_BLACKLIST
        );
    }

    public static class Bedrock {
        public static final ConfigInteger BEDROCK_INTERVAL = integer("bedrockInterval")
                .defaultValue(2)
                .range(1, 20)
                .build();

        public static final ConfigInteger BEDROCK_BLOCKS_PER_TICK = integer("bedrockBlocksPerTick")
                .defaultValue(6)
                .range(1, 64)
                .build();

        public static final ConfigBoolean BEDROCK_ALLOW_SIDE = bool("bedrockAllowSide")
                .defaultValue(false)
                .build();

        public static final ConfigStringList BEDROCK_WHITELIST = stringList("bedrockWhitelist")
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                BEDROCK_INTERVAL,
                BEDROCK_BLOCKS_PER_TICK,
                BEDROCK_ALLOW_SIDE,
                BEDROCK_WHITELIST
        );
    }

    public static class Print {
        // 閫夊尯绫诲�?
        public static final ConfigOptionList PRINT_SELECTION_TYPE = optionList("printSelectionType")
                .defaultValue(SelectionType.LITEMATICA_RENDER_LAYER)
                .setVisible(isFixedWorkArea)
                .build();

        // 鎶曞奖杞绘澗鏀剧疆鍗忚�?
        public static final ConfigBoolean EASY_PLACE_PROTOCOL = bool("easyPlaceProtocol")
                .defaultValue(false)
                .build();

        // 鍑┖鏀剧疆
        public static final ConfigBoolean PLACE_IN_AIR = bool("placeInAir")
                .defaultValue(true)
                .build();

        // 鎵撳嵃鐩爣鎺掑�?
        public static final ConfigBoolean PRINT_SORT_TARGETS = bool("printSortTargets")
                .defaultValue(false)
                .build();

        // 鏀剧疆闈㈡帓搴?
        public static final ConfigBoolean PRINT_SORT_SIDES = bool("printSortSides")
                .defaultValue(false)
                .build();

        // 閾佽建褰㈡€佷慨�?
        public static final ConfigBoolean REPAIR_RAIL_SHAPE = bool("printRepairRailShape")
                .defaultValue(false)
                .build();

        // 璺宠繃鏀剧疆
        public static final ConfigBoolean PRINT_SKIP = bool("printSkip")
                .defaultValue(false)
                .build();

        // 璺宠繃鏀剧疆鍚嶅�?
        public static final ConfigStringList PRINT_SKIP_LIST = stringList("printSkipList")
                .build();

        // 濮嬬粓娼滆
        public static final ConfigBoolean PRINT_FORCED_SNEAK = bool("printForcedSneak")
                .defaultValue(false)
                .build();

        // 瑕嗙洊鎵撳嵃
        public static final ConfigBoolean PRINT_REPLACE = bool("printReplace")
                .defaultValue(true)
                .build();

        // 瑕嗙洊鏂瑰潡鍒楄�?
        public static final ConfigStringList REPLACEABLE_LIST = stringList("printReplaceableList")
                .defaultValue(Blocks.SNOW, Blocks.LAVA, Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.SHORT_GRASS)
                .build();

        // 璺宠繃鍚按鏂瑰�?
        public static final ConfigBoolean SKIP_WATERLOGGED_BLOCK = bool("printSkipWaterlogged")
                .defaultValue(false)
                .build();

        // 鏇挎崲鐝婄憵
        public static final ConfigBoolean REPLACE_CORAL = bool("printReplaceCoral")
                .defaultValue(false)
                .build();

        // 鐮村啺鏀炬按
        public static final ConfigBooleanHotkeyed PRINT_ICE_FOR_WATER = booleanHotkey("printIceForWater")
                .defaultValue(false)
                .build();

        // 鑷姩鍘荤毊
        public static final ConfigBoolean STRIP_LOGS = bool("printAutoStripLogs")
                .defaultValue(false)
                .build();

        // 闊崇鐩掕嚜鍔ㄨ皟闊?
        public static final ConfigBoolean NOTE_BLOCK_TUNING = bool("printAutoTuning")
                .defaultValue(true)
                .build();

        // 渚︽祴鍣ㄥ畨鍏ㄦ斁缃?
        public static final ConfigBoolean SAFELY_OBSERVER = bool("printSafelyObserver")
                .defaultValue(true)
                .build();

        // 鍫嗚偉妗惰嚜鍔ㄥ～鍏?
        public static final ConfigBoolean FILL_COMPOSTER = bool("printAutoFillComposter")
                .defaultValue(false)
                .build();

        // 鍫嗚偉妗剁櫧鍚嶅�?
        public static final ConfigStringList FILL_COMPOSTER_WHITELIST = stringList("printAutoFillComposterWhitelist")
                .setVisible(FILL_COMPOSTER::getBooleanValue)
                .build();

        // 鍐滀綔鐗╁偓�?
        public static final ConfigBoolean BONEMEAL_CROPS = bool("printBonemealCrops")
                .defaultValue(false)
                .build();

        // 鍐滀綔鐗╁偓鐔熻繛鐐规�?
        public static final ConfigInteger BONEMEAL_CROPS_CLICKS = integer("printBonemealCropsClicks")
                .defaultValue(10)
                .range(1, 32)
                .setVisible(BONEMEAL_CROPS::getBooleanValue)
                .build();

        // 鐮村潖閿欒鏂瑰�?
        public static final ConfigBoolean BREAK_WRONG_BLOCK = bool("printBreakWrongBlock")
                .defaultValue(false)
                .build();

        // 鐮村潖澶氫綑鏂瑰�?
        public static final ConfigBoolean BREAK_EXTRA_BLOCK = bool("printBreakExtraBlock")
                .defaultValue(false)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PRINT_SELECTION_TYPE,
                EASY_PLACE_PROTOCOL,
                PLACE_IN_AIR,
                PRINT_SORT_TARGETS,
                PRINT_SORT_SIDES,
                REPAIR_RAIL_SHAPE,
                PRINT_FORCED_SNEAK,
                BREAK_WRONG_BLOCK,
                BREAK_EXTRA_BLOCK,
                PRINT_SKIP,
                PRINT_SKIP_LIST,
                PRINT_REPLACE,
                REPLACEABLE_LIST,
                SKIP_WATERLOGGED_BLOCK,
                PRINT_ICE_FOR_WATER,
                SAFELY_OBSERVER,
                STRIP_LOGS,
                NOTE_BLOCK_TUNING,
                REPLACE_CORAL,
                FILL_COMPOSTER,
                FILL_COMPOSTER_WHITELIST,
                BONEMEAL_CROPS
                , BONEMEAL_CROPS_CLICKS
        );
    }

    public static class Mine {
        // 閫夊尯绫诲�?
        public static final ConfigOptionList MINE_SELECTION_TYPE = optionList("mineSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .setVisible(isFixedWorkArea)
                .build();

        public static final ConfigBoolean AUTO_STORE_MINING_DROPS = bool("autoStoreMiningDropsInShulkers")
                .defaultValue(false)
                .build();

        public static final ConfigOptionList EXCAVATE_LIMITER = optionList("excavateLimiter")
                .defaultValue(ExcavateListMode.CUSTOM)
                .build();

        // 鎸栨帢妯″紡闄愬�?
        public static final ConfigOptionList EXCAVATE_LIMIT = optionList("excavateLimit")
                .defaultValue(UsageRestriction.ListType.NONE)
                .setVisible(isExcavateCustom)
                .build();

        // 鎸栨帢鐧藉悕�?
        public static final ConfigStringList EXCAVATE_WHITELIST = stringList("excavateWhitelist")
                .setVisible(isExcavateWhitelist)
                .build();

        // 鎸栨帢榛戝悕�?
        public static final ConfigStringList EXCAVATE_BLACKLIST = stringList("excavateBlacklist")
                .setVisible(isExcavateBlacklist)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                MINE_SELECTION_TYPE,          // 鎸栨�?- 閫夊尯绫诲�?
                AUTO_STORE_MINING_DROPS,
                EXCAVATE_LIMITER,             // 鎸栨�?- 鎸栨帢妯″紡闄愬埗鍣?
                EXCAVATE_LIMIT,               // 鎸栨�?- 鎸栨帢妯″紡闄愬�?
                EXCAVATE_WHITELIST,           // 鎸栨�?- 鎸栨帢鐧藉悕�?
                EXCAVATE_BLACKLIST            // 鎸栨�?- 鎸栨帢榛戝悕�?
        );
    }

    public static class Magnet {
        public static final ConfigBoolean ENABLED = bool("autoCollectMiningDrops")
                .defaultValue(false)
                .build();

        public static final ConfigInteger RANGE = integer("autoCollectMiningDropsRange")
                .defaultValue(8)
                .range(1, 32)
                .setVisible(ENABLED::getBooleanValue)
                .build();

        public static final ConfigBoolean FILTER_ENABLED = bool("pickupFilterEnabled")
                .defaultValue(false)
                .setVisible(ENABLED::getBooleanValue)
                .build();

        public static final ConfigOptionList FILTER_MODE = optionList("pickupFilterMode")
                .defaultValue(PickupFilterMode.MINE)
                .setVisible(isPickupFilterEnabled)
                .build();

        public static final ConfigOptionList CUSTOM_LIST_TYPE = optionList("pickupCustomListType")
                .defaultValue(UsageRestriction.ListType.BLACKLIST)
                .setVisible(isPickupCustomFilter)
                .build();

        public static final ConfigStringList CUSTOM_WHITELIST = stringList("pickupCustomWhitelist")
                .setVisible(isPickupCustomWhitelist)
                .build();

        public static final ConfigStringList CUSTOM_BLACKLIST = stringList("pickupCustomBlacklist")
                .setVisible(isPickupCustomBlacklist)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED,
                RANGE,
                FILTER_ENABLED,
                FILTER_MODE,
                CUSTOM_LIST_TYPE,
                CUSTOM_WHITELIST,
                CUSTOM_BLACKLIST
        );
    }

    public static class Clear {
        public static final ConfigBoolean CLEAR_FLUID_ENABLED = bool("clearFluidEnabled")
                .defaultValue(true)
                .build();

        public static final ConfigBoolean CLEAR_MINE_ENABLED = bool("clearMineEnabled")
                .defaultValue(true)
                .build();

        public static final ConfigBoolean CLEAR_BEDROCK_ENABLED = bool("clearBedrockEnabled")
                .defaultValue(true)
                .build();

        public static final ConfigInteger CLEAR_MAX_RETRIES = integer("clearMaxRetries")
                .defaultValue(3)
                .range(0, 8)
                .build();

        public static final ConfigOptionList CLEAR_SELECTION_TYPE = optionList("clearSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                CLEAR_FLUID_ENABLED,
                CLEAR_MINE_ENABLED,
                CLEAR_BEDROCK_ENABLED,
                CLEAR_MAX_RETRIES,
                CLEAR_SELECTION_TYPE
        );
    }

    public static class Fill {
        // 閫夊尯绫诲�?
        public static final ConfigOptionList FILL_SELECTION_TYPE = optionList("fillSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .setVisible(isFixedWorkArea)
                .build();

        // 濉厖鏂瑰潡妯″紡
        public static final ConfigOptionList FILL_BLOCK_MODE = optionList("fillBlockMode")
                .defaultValue(FillBlockModeType.BLOCKLIST)
                .build();

        // 濉厖鏂瑰潡鍚嶅�?
        public static final ConfigStringList FILL_BLOCK_LIST = stringList("fillBlockList")
                .defaultValue(Blocks.COBBLESTONE)
                .setVisible(isBlocklist)
                .build();

        // 妯″紡鏈濆�?
        public static final ConfigOptionList FILL_BLOCK_FACING = optionList("fillModeFacing")
                .defaultValue(FillModeFacingType.NONE)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                FILL_SELECTION_TYPE,          // 濉�?- 閫夊尯绫诲�?
                FILL_BLOCK_MODE,              // 濉�?- 濉厖鏂瑰潡妯″紡
                FILL_BLOCK_LIST,              // 濉�?- 濉厖鏂瑰潡鍚嶅�?
                FILL_BLOCK_FACING             // 濉�?- 妯″紡鏈濆�?
        );
    }

    public static class Fluid {

        // 閫夊尯绫诲�?
        public static final ConfigOptionList FLUID_SELECTION_TYPE = optionList("fluidSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .setVisible(isFixedWorkArea)
                .build();

        // 濉厖娴佸姩娑蹭�?
        public static final ConfigBoolean FILL_FLOWING_FLUID = bool("fluidModeFillFlowing")
                .defaultValue(true)
                .build();

        // 鏂瑰潡鍚嶅崟
        public static final ConfigStringList FLUID_REPLACE_BLOCK_LIST = stringList("fluidReplaceBlockList")
                .defaultValue(Blocks.SAND)
                .build();

        // 娑蹭綋鍚嶅崟
        public static final ConfigStringList FLUID_LIST = stringList("fluidList")
                .defaultValue(Blocks.WATER, Blocks.LAVA)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                FLUID_SELECTION_TYPE,         // 鎺掓祦浣?- 閫夊尯绫诲�?
                FILL_FLOWING_FLUID,           // 鎺掓祦浣?- 濉厖娴佸姩娑蹭�?
                FLUID_REPLACE_BLOCK_LIST,             // 鎺掓祦浣?- 鏂瑰潡鍚嶅崟
                FLUID_LIST                    // 鎺掓祦浣?- 娑蹭綋鍚嶅崟
        );
    }

    public static class Hotkeys {
        // 鎵撳紑璁剧疆鑿滃�?
        public static final ConfigHotkey OPEN_SCREEN = hotkey("openScreen")
                .defaultStorageString("Z,Y")
                .build();

        // 鍏抽棴鍏ㄩ儴妯″紡
        public static final ConfigHotkey CLOSE_ALL_MODE = hotkey("closeAllMode")
                .defaultStorageString("LEFT_CONTROL,G")
                .build();

        // 鍒囨崲妯″紡
        public static final ConfigHotkey SWITCH_PRINTER_MODE = hotkey("switchPrinterMode")
                .bindConfig(Core.WORK_MODE_TYPE)
                .setVisible(isSingle) // 浠呭崟妯″紡鏃舵樉绀?
                .build();

        // 鐩存帴鍚敤娓呴櫎妯″紡
        public static final ConfigHotkey ACTIVATE_CLEAR_MODE = hotkey("activateClearMode")
                .keybindSettings(KeybindSettings.PRESS_ALLOWEXTRA_EMPTY)
                .setVisible(isSingle)
                .build();


        // Multi-mode Bedrock toggle; single mode is selected by WORK_MODE_TYPE.
        public static final ConfigBooleanHotkeyed BEDROCK = booleanHotkey("bedrock")
                .defaultValue(false)
                .setVisible(isMulti)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OPEN_SCREEN,                  // 鎵撳紑璁剧疆鑿滃�?
                Core.WORK_SWITCH,
                CLOSE_ALL_MODE,               // 鍏抽棴鍏ㄩ儴妯″紡
                SWITCH_PRINTER_MODE,          // 鍒囨崲妯″紡
                ACTIVATE_CLEAR_MODE,           // 鍚敤娓呴櫎妯″紡

                // 澶氭ā
                Core.PRINT,
                Core.MINE,                // 鎸栨�?
                Core.FILL,                    // 濉�?
                Core.FLUID,                  // 鎺掓祦浣?
                BEDROCK                          // 鐮村熀宀?
        );
    }

    @Override
    public void load() {
        File settingFile = new File(FILE_PATH);
        if (settingFile.isFile() && settingFile.exists()) {
            //#if MC >= 12111
            JsonElement jsonElement = JsonUtils.parseJsonFile(settingFile.toPath());
            //#else
            //$$ JsonElement jsonElement = JsonUtils.parseJsonFile(settingFile);
            //#endif
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject obj = jsonElement.getAsJsonObject();
                ConfigUtils.readConfigBase(obj, Reference.MOD_ID, OPTIONS);
            }
        }
    }


    @Override
    public void save() {
        File settingFile = new File(FILE_PATH);
        if ((CONFIG_DIR.exists() && CONFIG_DIR.isDirectory()) || CONFIG_DIR.mkdirs()) {
            JsonObject configRoot = new JsonObject();
            ConfigUtils.writeConfigBase(configRoot, Reference.MOD_ID, OPTIONS);
            //#if MC >= 12111
            JsonUtils.writeJsonToFile(configRoot, settingFile.toPath());
            //#else
            //$$ JsonUtils.writeJsonToFile(configRoot, settingFile);
            //#endif
        }
    }

    public static void init() {
        Configs.INSTANCE.load();
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        InputEventHandler.getInputManager().registerKeyboardInputHandler(InputHandler.getInstance());
        //#if MC > 12006
        fi.dy.masa.malilib.registry.Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new fi.dy.masa.malilib.util.data.ModInfo(Reference.MOD_ID, Reference.MOD_NAME, ConfigUi::new)
        );
        //#endif
    }

    public static void saveToFile() {
        Configs.INSTANCE.save();
    }
}
