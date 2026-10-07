package com.matuvent.mineturn.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.Map;

@Mixin(ItemCooldowns.class)
public abstract class ItemCooldownAccess implements com.matuvent.mineturn.api.RemainingItemCooldown {
    @Shadow private int tickCount;
    @Shadow private Map<Item,?> cooldowns;
    public int mineturn$remaining(Item item){
        var entry=cooldowns.get(item);
        return entry==null?0:Math.max(0,((CooldownEndAccess)entry).mineturn$end()-tickCount);
    }
}
