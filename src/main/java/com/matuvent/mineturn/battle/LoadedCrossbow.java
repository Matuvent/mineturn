package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

/** Native preloaded ammunition is already paid for. Never replace it with inventory ammunition. */
final class LoadedCrossbow {
    static ChargedProjectiles contents(ItemStack weapon, CombatData.Action action) {
        if (!weapon.is(Items.CROSSBOW) || action.ranged() == null
                || !java.util.Set.of("mineturn:projectile", "mineturn:firework").contains(action.effect()))
            return ChargedProjectiles.EMPTY;
        return weapon.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
    }

    static ItemStack ammunition(ItemStack weapon, CombatData.Action action) {
        var loaded = contents(weapon, action);
        if (loaded.isEmpty()) return ItemStack.EMPTY;
        var items = loaded.getItems();
        var first = items.getFirst();
        // Native loading produces one or three identical projectiles. Preserve unsupported custom loads intact.
        if ((items.size() != 1 && items.size() != 3) || first.isEmpty()
                || items.stream().anyMatch(item -> item.getCount() != 1 || !ItemStack.isSameItemSameComponents(first, item)))
            throw new IllegalArgumentException("这把弩的自定义装填组合尚未适配，已保留装填内容。");
        if (action.ranged().ammoCount() != 1
                || !BuiltInRegistries.ITEM.getKey(first.getItem()).toString().equals(action.ranged().ammunition()))
            throw new IllegalArgumentException("弩已装填其他弹药，请选择与装填内容对应的射击动作。");
        return first.copy();
    }

    static boolean consume(ItemStack weapon, CombatData.Action action) {
        if (ammunition(weapon, action).isEmpty()) return false;
        weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        return true;
    }

    private LoadedCrossbow() {}
}
