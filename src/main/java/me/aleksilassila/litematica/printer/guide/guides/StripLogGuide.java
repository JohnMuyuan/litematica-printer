package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.Reference;
//#if MC >= 260300
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//$$ import net.minecraft.resources.Identifier;
//$$ import java.util.HashMap;
//#else
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
//#endif
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;

/**
 * 去皮原木
 */
public class StripLogGuide extends Guide {

    //#if MC >= 260300
    //$$ // Stripping is a data-driven block transformer since 26.3; every strippable block has a "stripped_" twin
    //$$ private static final Map<Block, Block> STRIPPED_LOGS = collectStrippables();
    //$$
    //$$ private static Map<Block, Block> collectStrippables() {
    //$$     Map<Block, Block> strippables = new HashMap<>();
    //$$     for (Block stripped : BuiltInRegistries.BLOCK) {
    //$$         Identifier id = BuiltInRegistries.BLOCK.getKey(stripped);
    //$$         if (!id.getPath().startsWith("stripped_")) continue;
    //$$         Identifier source = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath().substring("stripped_".length()));
    //$$         BuiltInRegistries.BLOCK.getOptional(source).ifPresent(block -> strippables.put(block, stripped));
    //$$     }
    //$$     return strippables;
    //$$ }
    //#else
    @SuppressWarnings("all")
    private static final Map<Block, Block> STRIPPED_LOGS = AxeItemAccessor.getStrippables();
    //#endif

    public StripLogGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction.Axis axis = getProperty(requiredState, BlockStateProperties.AXIS).orElse(null);
        if (axis == null) return Result.PASS;

        Action action = new Action().setSides(axis);

        // 配置启用去皮时，可接受原版或去皮版本
        if (Configs.Print.STRIP_LOGS.getBooleanValue()) {
            for (Map.Entry<Block, Block> entry : STRIPPED_LOGS.entrySet()) {
                if (requiredBlock == entry.getValue()) {
                    action.setItems(entry.getValue().asItem(), entry.getKey().asItem());
                    return Result.success(action);
                }
            }
        }

        return Result.success(action);
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        Block stripped = STRIPPED_LOGS.get(currentBlock);
        if (stripped != null && stripped == requiredBlock) {
            return Result.success(new ClickAction().setItems(Reference.AXE_ITEMS));
        }
        return Result.SKIP;
    }
}
