package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;

/** Single prepaid, entity-locked hit. Real time never advances the pending strike. */
final class DelayedStrike implements CombatEffects.Effect {
    private static double delay(CombatData.Action action){
        var value=action.parameters().get("delay_av");
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("delayed_strike requires numeric delay_av");
        double result=value.getAsDouble();
        if(!Double.isFinite(result)||result<0.01||result>100000)throw new IllegalArgumentException("Invalid delay_av");
        return result;
    }
    @Override public void validateDefinition(CombatData.Action action){
        delay(action);
        if(action.self() || action.allied() || action.consume()!=0 || action.ranged()!=null)
            throw new IllegalArgumentException("delayed_strike requires enemy target, consume=0 and no ranged check");
    }
    private static boolean validTarget(CombatEffects.Context context){
        return context.battle().enemies().contains(context.target())
                && context.source().distanceTo(context.target())<=context.action().range()
                && context.source().hasLineOfSight(context.target());
    }
    @Override public String validate(CombatEffects.Context context){
        if(!context.battle().canSchedule(delay(context.action())))return "延迟技能队列已满或战斗已结束。";
        return validTarget(context)?null:"目标超出范围、被遮挡或已不是敌人。";
    }
    @Override public void execute(CombatEffects.Context context){
        context.battle().afterChecked(delay(context.action()),DelayedStrike::validTarget,
                ready->ready.battle().hurt(ready.target(),(float)ready.action().amount()));
    }
}
