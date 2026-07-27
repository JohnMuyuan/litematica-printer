package me.aleksilassila.litematica.printer.mixin.printer.modmenu;

import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

@Pseudo
@Mixin(targets = "com.terraformersmc.modmenu.util.mod.fabric.FabricMod", remap = false)
public abstract class MixinFabricMod {
    @Inject(method = "getCredits", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void litematica_printer$localizeAuthor(CallbackInfoReturnable<SortedMap<String, Set<String>>> cir) {
        SortedMap<String, Set<String>> credits = cir.getReturnValue();
        if (credits == null || credits.isEmpty()) {
            return;
        }

        String localizedAuthor = I18n.get("litematica-printer.modmenu.author");
        SortedMap<String, Set<String>> localizedCredits = new TreeMap<>(credits.comparator());
        boolean changed = false;
        for (Map.Entry<String, Set<String>> entry : credits.entrySet()) {
            Set<String> names = new LinkedHashSet<>(entry.getValue());
            if (names.remove("JohnMuyuan") || names.remove("江木源")) {
                names.add(localizedAuthor);
                changed = true;
            }
            localizedCredits.put(entry.getKey(), names);
        }
        if (changed) {
            cir.setReturnValue(localizedCredits);
        }
    }
}
