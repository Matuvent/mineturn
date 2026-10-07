package com.matuvent.mineturn.api;

public final class EnderPearl implements CombatEffects.Effect {
    public static boolean action(com.matuvent.mineturn.data.CombatData.Action action){return action.effect().equals("mineturn:ender_pearl");}
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action action){
        if(!action.self() || action.ranged()!=null || action.consume()!=1 || !PotionTargets.ground(action))
            throw new IllegalArgumentException("ender_pearl requires self, ground_target, consume=1 and no ranged check");
    }
    @Override public String validate(CombatEffects.Context context){
        if(!(context.source() instanceof net.minecraft.server.level.ServerPlayer player) || !context.item().is(net.minecraft.world.item.Items.ENDER_PEARL))return "需要玩家使用末影珍珠。";
        if(player.isPassenger())return "请先离开坐骑再使用末影珍珠。";
        if(player.isSleeping())return "睡眠时不能使用末影珍珠。";
        return VanillaCombatItems.slotError(context);
    }
    @Override public void execute(CombatEffects.Context context){throw new IllegalStateException("Pearl requires a validated destination");}
}
