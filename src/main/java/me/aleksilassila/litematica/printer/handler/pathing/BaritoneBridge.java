package me.aleksilassila.litematica.printer.handler.pathing;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small reflective adapter so normal printer modes do not require Baritone. */
final class BaritoneBridge {
    private boolean resolutionAttempted;
    private boolean failureLogged;
    private Bindings bindings;
    private final Map<String, Object> originalSettings = new LinkedHashMap<>();
    private boolean settingsApplied;
    private boolean ownsPath;

    boolean isAvailable() {
        if (this.bindings != null) {
            return true;
        }
        if (this.resolutionAttempted) {
            return false;
        }
        this.resolutionAttempted = true;
        if (!FabricLoader.getInstance().isModLoaded("baritone")) {
            Reference.LOGGER.info("[AutoBedrock] Fabric Loader did not find a loaded Baritone mod");
            return false;
        }
        this.bindings = this.resolveBindings();
        return this.bindings != null;
    }

    boolean startBlock(BlockPos goalPos) {
        if (goalPos == null || !this.isAvailable()) {
            return false;
        }
        try {
            Bindings resolved = this.bindings;
            Object goal = resolved.goalBlockConstructor().newInstance(
                    goalPos.getX(), goalPos.getY(), goalPos.getZ());
            return this.startGoal(resolved, goal);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.logFailure("start block", exception);
            this.restoreSettings();
            return false;
        }
    }

    boolean startComposite(List<BlockPos> goalPositions) {
        if (goalPositions == null || goalPositions.isEmpty() || !this.isAvailable()) {
            return false;
        }
        if (goalPositions.size() == 1) {
            return this.startBlock(goalPositions.getFirst());
        }
        try {
            Bindings resolved = this.bindings;
            Object goals = Array.newInstance(resolved.goalInterface(), goalPositions.size());
            for (int index = 0; index < goalPositions.size(); index++) {
                BlockPos pos = goalPositions.get(index);
                Object goal = resolved.goalBlockConstructor().newInstance(pos.getX(), pos.getY(), pos.getZ());
                Array.set(goals, index, goal);
            }
            Object composite = resolved.goalCompositeConstructor().newInstance(goals);
            return this.startGoal(resolved, composite);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.logFailure("start composite", exception);
            this.restoreSettings();
            return false;
        }
    }

    boolean startXZ(int x, int z) {
        if (!this.isAvailable()) {
            return false;
        }
        try {
            Bindings resolved = this.bindings;
            Object goal = resolved.goalXZConstructor().newInstance(x, z);
            return this.startGoal(resolved, goal);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.logFailure("start XZ", exception);
            this.restoreSettings();
            return false;
        }
    }

    private boolean startGoal(Bindings resolved, Object goal) throws ReflectiveOperationException {
        this.applySettings(resolved);
        this.cancelInternal(resolved);
        Object process = resolved.customGoalProcessGetter().invoke(resolved.baritone());
        resolved.setGoalAndPath().invoke(process, goal);
        this.ownsPath = true;
        return true;
    }

    boolean isActive() {
        if (!this.isAvailable()) {
            return false;
        }
        Bindings resolved = this.bindings;
        try {
            Object process = resolved.customGoalProcessGetter().invoke(resolved.baritone());
            if (invokeBoolean(resolved.processIsActive(), process)) {
                return true;
            }
            Object behavior = resolved.pathingBehaviorGetter().invoke(resolved.baritone());
            return invokeBoolean(resolved.behaviorIsPathing(), behavior)
                    || invokeBoolean(resolved.behaviorHasPath(), behavior);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.logFailure("query", exception);
            return false;
        }
    }

    void cancel() {
        if (!this.ownsPath || !this.isAvailable()) {
            return;
        }
        try {
            this.cancelInternal(this.bindings);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            this.logFailure("cancel", exception);
        } finally {
            this.ownsPath = false;
        }
    }

    private void cancelInternal(Bindings resolved) throws ReflectiveOperationException {
        Object behavior = resolved.pathingBehaviorGetter().invoke(resolved.baritone());
        resolved.behaviorCancelEverything().invoke(behavior);
        try {
            if (invokeBoolean(resolved.behaviorIsPathing(), behavior)
                    || invokeBoolean(resolved.behaviorHasPath(), behavior)) {
                resolved.behaviorForceCancel().invoke(behavior);
            }
        } catch (ReflectiveOperationException ignored) {
            // forceCancel is not present in every supported Baritone build.
        }
        if (resolved.inputOverrideGetter() != null && resolved.inputClearAllKeys() != null) {
            try {
                Object input = resolved.inputOverrideGetter().invoke(resolved.baritone());
                resolved.inputClearAllKeys().invoke(input);
            } catch (ReflectiveOperationException ignored) {
                // Older Baritone versions do not expose the input override handler.
            }
        }
    }

    private void applySettings(Bindings resolved) {
        Object settings = resolved.settings();
        this.setSettingIfPresent(settings, "allowBreak", Configs.AutoClear.PATHING_ALLOW_BREAK.getBooleanValue());
        this.setSettingIfPresent(settings, "allowPlace", Configs.AutoClear.PATHING_ALLOW_PLACE.getBooleanValue());
        this.setSettingIfPresent(settings, "allowSprint", Configs.AutoClear.PATHING_ALLOW_SPRINT.getBooleanValue());
        this.setSettingIfPresent(settings, "allowParkour", false);
        this.setSettingIfPresent(settings, "allowParkourPlace", false);
        this.setSettingIfPresent(settings, "allowWaterBucketFall", false);
        this.setSettingIfPresent(settings, "allowDiagonalAscend", false);
        this.setSettingIfPresent(settings, "allowDiagonalDescend", false);
        this.setSettingIfPresent(settings, "allowInventory", false);
        this.setSettingIfPresent(settings, "freeLook", true);
        this.setSettingIfPresent(settings, "blockFreeLook", true);
        this.setSettingIfPresent(settings, "renderPath", false);
        this.setSettingIfPresent(settings, "renderGoal", false);
        this.setSettingIfPresent(settings, "renderSelectionBoxes", false);
        this.setSettingIfPresent(settings, "renderSelection", false);
        this.setSettingIfPresent(settings, "antiCheatCompatibility", true);
        this.setSettingIfPresent(settings, "itemSaver", true);
        this.setSettingIfPresent(settings, "itemSaverThreshold", Configs.AutoClear.MIN_TOOL_DURABILITY.getIntegerValue());
        this.protectBlocksIfPresent(settings, List.of(
                Blocks.BEDROCK,
                Blocks.PISTON,
                Blocks.PISTON_HEAD,
                Blocks.MOVING_PISTON,
                Blocks.REDSTONE_TORCH,
                Blocks.REDSTONE_WALL_TORCH,
                Blocks.SLIME_BLOCK));
        this.settingsApplied = !this.originalSettings.isEmpty();
    }

    private void setSettingIfPresent(Object settings, String name, Object value) {
        try {
            SettingAccess access = settingAccess(settings, name);
            this.originalSettings.putIfAbsent(name, copySettingValue(access.value()));
            access.valueField().set(access.setting(), value);
        } catch (NoSuchFieldException exception) {
            Reference.LOGGER.debug("[AutoBedrock] Baritone setting '{}' is unavailable; keeping its default", name);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Reference.LOGGER.debug("[AutoBedrock] Unable to apply optional Baritone setting '{}'", name, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void protectBlocksIfPresent(Object settings, List<Block> protectedBlocks) {
        String name = "blocksToDisallowBreaking";
        try {
            SettingAccess access = settingAccess(settings, name);
            if (!(access.value() instanceof List<?> rawList)) {
                return;
            }
            this.originalSettings.putIfAbsent(name, new ArrayList<>(rawList));
            List<Block> blocks = new ArrayList<>((List<Block>) rawList);
            for (Block block : protectedBlocks) {
                if (!blocks.contains(block)) {
                    blocks.add(block);
                }
            }
            access.valueField().set(access.setting(), blocks);
        } catch (NoSuchFieldException exception) {
            Reference.LOGGER.debug("[AutoBedrock] Baritone protected-block setting is unavailable");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Reference.LOGGER.debug("[AutoBedrock] Unable to protect bedrock-machine blocks from Baritone", exception);
        }
    }

    void shutdown() {
        this.cancel();
        this.restoreSettings();
        this.ownsPath = false;
    }

    private void restoreSettings() {
        if ((!this.settingsApplied && this.originalSettings.isEmpty()) || this.bindings == null) {
            return;
        }
        Object settings = this.bindings.settings();
        try {
            for (Map.Entry<String, Object> entry : this.originalSettings.entrySet()) {
                try {
                    SettingAccess access = settingAccess(settings, entry.getKey());
                    access.valueField().set(access.setting(), copySettingValue(entry.getValue()));
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    Reference.LOGGER.debug("[AutoBedrock] Unable to restore Baritone setting '{}'", entry.getKey(), exception);
                }
            }
        } finally {
            this.originalSettings.clear();
            this.settingsApplied = false;
        }
    }

    private static SettingAccess settingAccess(Object settings, String name) throws ReflectiveOperationException {
        Field settingField = settings.getClass().getField(name);
        Object setting = settingField.get(settings);
        Field valueField;
        try {
            valueField = setting.getClass().getField("value");
        } catch (NoSuchFieldException ignored) {
            valueField = findObfuscatedSettingValueField(setting.getClass());
        }
        return new SettingAccess(setting, valueField, valueField.get(setting));
    }

    private static Field findObfuscatedSettingValueField(Class<?> settingClass) throws NoSuchFieldException {
        for (Field field : settingClass.getFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)
                    && field.getType() != boolean.class && !field.isSynthetic()) {
                return field;
            }
        }
        throw new NoSuchFieldException(settingClass.getName() + ".<current value>");
    }

    private static Object copySettingValue(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : value;
    }

    private Bindings resolveBindings() {
        Throwable standardFailure;
        try {
            Bindings standard = resolveStandardBindings();
            Reference.LOGGER.info("[AutoBedrock] Baritone detected through the standard API");
            return standard;
        } catch (ReflectiveOperationException | LinkageError exception) {
            standardFailure = exception;
            Reference.LOGGER.debug("[AutoBedrock] Standard Baritone API is unavailable; trying the standalone adapter", exception);
        }

        try {
            Bindings standalone = resolveStandaloneBindings();
            Reference.LOGGER.info("[AutoBedrock] Baritone detected through the 1.15 standalone adapter");
            return standalone;
        } catch (ReflectiveOperationException | LinkageError exception) {
            exception.addSuppressed(standardFailure);
            this.logFailure("resolve", exception);
            return null;
        }
    }

    private static Bindings resolveStandardBindings() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiClass.getMethod("getProvider").invoke(null);
        Object baritone = invokeNoArgs(provider, "getPrimaryBaritone");
        Object settings = apiClass.getMethod("getSettings").invoke(null);
        Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
        Class<?> goalClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
        Class<?> compositeClass = Class.forName("baritone.api.pathing.goals.GoalComposite");
        Class<?> xzClass = Class.forName("baritone.api.pathing.goals.GoalXZ");
        Constructor<?> goalConstructor = goalClass.getConstructor(int.class, int.class, int.class);
        Constructor<?> compositeConstructor = compositeClass.getConstructor(
                Array.newInstance(goalInterface, 0).getClass());
        Constructor<?> xzConstructor = xzClass.getConstructor(int.class, int.class);

        Method processGetter = baritone.getClass().getMethod("getCustomGoalProcess");
        Object process = processGetter.invoke(baritone);
        Method behaviorGetter = baritone.getClass().getMethod("getPathingBehavior");
        Object behavior = behaviorGetter.invoke(baritone);
        Method inputGetter = findNamedNoArgOrNull(baritone.getClass(), "getInputOverrideHandler", null);
        Object input = inputGetter == null ? null : inputGetter.invoke(baritone);

        return new Bindings(
                baritone,
                settings,
                goalInterface,
                goalConstructor,
                compositeConstructor,
                xzConstructor,
                processGetter,
                behaviorGetter,
                inputGetter,
                findSingleArgumentMethod(process.getClass(), "setGoalAndPath", goalClass),
                process.getClass().getMethod("isActive"),
                behavior.getClass().getMethod("isPathing"),
                behavior.getClass().getMethod("hasPath"),
                behavior.getClass().getMethod("cancelEverything"),
                behavior.getClass().getMethod("forceCancel"),
                input == null ? null : findNamedNoArgOrNull(input.getClass(), "clearAllKeys", void.class));
    }

    private static Bindings resolveStandaloneBindings() throws ReflectiveOperationException {
        Class<?> globalsClass = Class.forName("baritone.c");
        Class<?> providerType = Class.forName("baritone.api.IBaritoneProvider");
        Class<?> baritoneType = Class.forName("baritone.d");
        Class<?> settingsType = Class.forName("baritone.e");
        Class<?> processType = Class.forName("baritone.jw");
        Class<?> behaviorType = Class.forName("baritone.fc");
        Class<?> inputType = Class.forName("baritone.le");
        Class<?> goalType = Class.forName("baritone.ca");
        Class<?> goalCompositeType = Class.forName("baritone.cb");
        Class<?> goalXZType = Class.forName("baritone.ci");
        Class<?> goalInterface = Class.forName("baritone.by");

        Object provider = findStaticNoArgReturning(globalsClass, providerType).invoke(null);
        Object baritone = findNoArgReturning(provider.getClass(), baritoneType).invoke(provider);
        Object settings = findStaticNoArgReturning(globalsClass, settingsType).invoke(null);
        Method processGetter = findNoArgReturning(baritoneType, processType);
        Method behaviorGetter = findNoArgReturning(baritoneType, behaviorType);
        Method inputGetter = findNoArgReturning(baritoneType, inputType);
        Object process = processGetter.invoke(baritone);
        Object behavior = behaviorGetter.invoke(baritone);
        Object input = inputGetter.invoke(baritone);

        Method setGoalAndPath = findDefaultSingleArgumentMethod(process.getClass(), goalInterface);
        return new Bindings(
                baritone,
                settings,
                goalInterface,
                goalType.getConstructor(int.class, int.class, int.class),
                goalCompositeType.getConstructor(Array.newInstance(goalInterface, 0).getClass()),
                goalXZType.getConstructor(int.class, int.class),
                processGetter,
                behaviorGetter,
                inputGetter,
                setGoalAndPath,
                findNamedNoArg(process.getClass(), "a", boolean.class),
                findNamedNoArg(behavior.getClass(), "a", boolean.class),
                findNamedNoArg(behavior.getClass(), "b", boolean.class),
                findNamedNoArg(behavior.getClass(), "c", boolean.class),
                findNamedNoArg(behavior.getClass(), "a", void.class),
                findNamedNoArg(input.getClass(), "a", void.class));
    }

    private void logFailure(String operation, Throwable throwable) {
        if (this.failureLogged) {
            return;
        }
        this.failureLogged = true;
        Reference.LOGGER.warn("[AutoBedrock] Baritone {} failed; automatic pathing is unavailable", operation, throwable);
    }

    private static Object invokeNoArgs(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static boolean invokeBoolean(Method method, Object target) throws ReflectiveOperationException {
        Object result = method.invoke(target);
        return result instanceof Boolean value && value;
    }

    private static Method findStaticNoArgReturning(Class<?> owner, Class<?> returnType) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                    && method.getReturnType() == returnType) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + ".<static no-arg -> " + returnType.getName() + ">");
    }

    private static Method findNoArgReturning(Class<?> owner, Class<?> returnType) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                    && method.getReturnType() == returnType) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + ".<no-arg -> " + returnType.getName() + ">");
    }

    private static Method findNamedNoArg(Class<?> owner, String name, Class<?> returnType)
            throws NoSuchMethodException {
        Method method = findNamedNoArgOrNull(owner, name, returnType);
        if (method != null) {
            return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "() -> " + returnType.getName());
    }

    private static Method findNamedNoArgOrNull(Class<?> owner, String name, Class<?> returnType) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0
                    && (returnType == null || method.getReturnType() == returnType)) {
                return method;
            }
        }
        return null;
    }

    private static Method findDefaultSingleArgumentMethod(Class<?> owner, Class<?> argumentType)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.isDefault() && method.getParameterCount() == 1
                    && method.getReturnType() == void.class
                    && method.getParameterTypes()[0].isAssignableFrom(argumentType)) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + ".<default goal-and-path>");
    }

    private static Method findSingleArgumentMethod(Class<?> owner, String name, Class<?> argumentType)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(argumentType)) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private record SettingAccess(Object setting, Field valueField, Object value) { }

    private record Bindings(
            Object baritone,
            Object settings,
            Class<?> goalInterface,
            Constructor<?> goalBlockConstructor,
            Constructor<?> goalCompositeConstructor,
            Constructor<?> goalXZConstructor,
            Method customGoalProcessGetter,
            Method pathingBehaviorGetter,
            Method inputOverrideGetter,
            Method setGoalAndPath,
            Method processIsActive,
            Method behaviorIsPathing,
            Method behaviorHasPath,
            Method behaviorCancelEverything,
            Method behaviorForceCancel,
            Method inputClearAllKeys) { }
}
