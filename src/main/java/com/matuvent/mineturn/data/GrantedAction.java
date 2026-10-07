package com.matuvent.mineturn.data;

import com.google.gson.JsonObject;
import com.matuvent.mineturn.api.CombatConditions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.*;

/** All conditions must hold; definitions are immutable and reevaluated at execution. */
public record GrantedAction(String action,String name,String icon,int order,boolean enabled,
                            Map<EquipmentSlot,String> equipment,List<String> effects,List<String> conditions,
                            com.matuvent.mineturn.api.CombatResources.Cost cost) {
    public GrantedAction(String action,String name,String icon,int order,boolean enabled,Map<EquipmentSlot,String> equipment,List<String> effects,List<String> conditions){this(action,name,icon,order,enabled,equipment,effects,conditions,null);}
    public GrantedAction {equipment=Map.copyOf(equipment);effects=List.copyOf(effects);conditions=List.copyOf(conditions);}
    public boolean available(ServerPlayer player) {
        return enabled && equipment.entrySet().stream().allMatch(e->BuiltInRegistries.ITEM.getKey(player.getItemBySlot(e.getKey()).getItem()).toString().equals(e.getValue()))
                && effects.stream().allMatch(id->player.hasEffect(BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(id)).orElseThrow()))
                && conditions.stream().allMatch(id->CombatConditions.test(id,player));
    }
    public static GrantedAction parse(JsonObject json) {
        String action=ResourceLocation.parse(json.get("action").getAsString()).toString();
        String name=json.get("name").getAsString(),icon=ResourceLocation.parse(json.get("icon").getAsString()).toString();
        if(name.isBlank() || name.length()>64 || !BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(icon)))throw new IllegalArgumentException("Invalid granted action name/icon");
        var equipment=new EnumMap<EquipmentSlot,String>(EquipmentSlot.class);
        if(json.has("equipment"))json.getAsJsonObject("equipment").entrySet().forEach(e->{
            var slot=EquipmentSlot.byName(e.getKey());String item=ResourceLocation.parse(e.getValue().getAsString()).toString();
            if(!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(item)))throw new IllegalArgumentException("Unknown equipment item "+item);
            equipment.put(slot,item);
        });
        var effects=new ArrayList<String>();var conditions=new ArrayList<String>();
        if(json.has("effects"))for(var entry:json.getAsJsonArray("effects")) {
            String id=ResourceLocation.parse(entry.getAsString()).toString();
            if(!BuiltInRegistries.MOB_EFFECT.containsKey(ResourceLocation.parse(id)))throw new IllegalArgumentException("Unknown effect "+id);
            effects.add(id);
        }
        if(json.has("conditions"))for(var entry:json.getAsJsonArray("conditions")) {
            String id=ResourceLocation.parse(entry.getAsString()).toString();
            if(!CombatConditions.contains(id))throw new IllegalArgumentException("Unknown combat condition "+id);
            conditions.add(id);
        }
        if(effects.size()+conditions.size()>32)throw new IllegalArgumentException("Too many grant conditions");
        com.matuvent.mineturn.api.CombatResources.Cost cost=null;
        if(json.has("cost")) {
            var value=json.getAsJsonObject("cost");
            cost=new com.matuvent.mineturn.api.CombatResources.Cost(value.get("resource").getAsString(),value.get("amount").getAsDouble());
        }
        return new GrantedAction(action,name,icon,json.has("order")?json.get("order").getAsInt():0,
                !json.has("enabled")||json.get("enabled").getAsBoolean(),equipment,effects,conditions,cost);
    }
}
