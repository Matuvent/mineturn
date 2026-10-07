package com.matuvent.mineturn.api;

public final class ChorusFruit implements CombatEffects.Effect {
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action action){
        if(!action.self() || action.consume()!=0 || action.ranged()!=null || PotionTargets.ground(action))
            throw new IllegalArgumentException("chorus_fruit requires self, consume=0 and no ground/ranged target");
    }
    @Override public String validate(CombatEffects.Context context){
        if(!(context.source() instanceof net.minecraft.server.level.ServerPlayer player)
                || !context.item().is(net.minecraft.world.item.Items.CHORUS_FRUIT) || context.item().getFoodProperties(player)==null)return "需要可食用的紫颂果。";
        if(player.isPassenger() || player.isSleeping())return "骑乘或睡眠时不能使用紫颂果。";
        if(!context.battle().chorusReady())return "紫颂果仍在冷却。";
        return VanillaCombatItems.slotError(context);
    }
    @Override public void execute(CombatEffects.Context context){
        VanillaCombatItems.food(context);
        context.battle().chorusTeleport();
    }
}
