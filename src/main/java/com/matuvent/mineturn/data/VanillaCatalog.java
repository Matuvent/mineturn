package com.matuvent.mineturn.data;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.SwordItem;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lowest-priority, registry-complete vanilla item defaults. Explicit data files/components win. */
public final class VanillaCatalog {
    public static final Set<String> SPECIAL_FOOD=Set.of("minecraft:chorus_fruit","minecraft:honey_bottle",
            "minecraft:suspicious_stew","minecraft:mushroom_stew","minecraft:rabbit_stew","minecraft:beetroot_soup");
    static void addItems(Map<String,CombatItem> items,Map<String,CombatData.Action> actions,Map<String,String> sources) {
        if(!actions.containsKey("mineturn:eat") || !actions.containsKey("mineturn:melee"))
            throw new IllegalArgumentException("Vanilla catalog requires mineturn:eat and mineturn:melee actions");
        BuiltInRegistries.ITEM.forEach(item->{
            var id=BuiltInRegistries.ITEM.getKey(item);if(!id.getNamespace().equals("minecraft") || items.containsKey(id.toString()))return;
            CombatItem config;
            if(item instanceof SwordItem || item instanceof DiggerItem || item instanceof net.minecraft.world.item.MaceItem)config=new CombatItem(true,List.of("mineturn:melee"),2.5);
            else if(item.getDefaultInstance().has(DataComponents.FOOD) && !SPECIAL_FOOD.contains(id.toString()))config=new CombatItem(true,List.of("mineturn:eat"),1);
            else config=new CombatItem(false,List.of(),2.5);
            items.put(id.toString(),config);sources.put("items/"+id,"mineturn:catalog/vanilla");
        });
    }
    private VanillaCatalog(){}
}
