package com.matuvent.mineturn.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/** Player throw with battle-managed loyalty and native channeling; riptide remains gated. */
public final class PlayerTrident implements CombatEffects.Effect {
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action action) {
        if(action.self() || action.consume()!=0 || action.ranged()==null || action.ranged().ammoCount()!=0)
            throw new IllegalArgumentException("player_trident requires ranged, consume=0, ammo_count=0");
        double delay=returnAv(action);
        if(!Double.isFinite(delay) || delay<5 || delay>10000)throw new IllegalArgumentException("loyalty_return_av must be 5..10000");
    }
    public static double returnAv(com.matuvent.mineturn.data.CombatData.Action action) {
        return action.parameters().has("loyalty_return_av")?action.parameters().get("loyalty_return_av").getAsDouble():100;
    }
    @Override public String validate(CombatEffects.Context context) {
        if(!(context.source() instanceof ServerPlayer player) || !context.item().is(Items.TRIDENT))return "需要玩家使用三叉戟。";
        var stack=context.item();
        if(stack.getCount()!=1)return "请将三叉戟分为单件后投掷。";
        if(stack.isDamageableItem() && stack.getDamageValue()>=stack.getMaxDamage()-1)return "三叉戟耐久过低，无法投掷。";
            if(EnchantmentHelper.getTridentSpinAttackStrength(stack,player)>0)
                return "激流三叉戟不能投出，请选择激流冲刺或近战。";
        return VanillaCombatItems.slotError(context);
    }
    @Override public void execute(CombatEffects.Context context) {
        var trident=new net.minecraft.world.entity.projectile.ThrownTrident(context.source().level(),context.source(),context.item().copy());
        try {
            context.target().invulnerableTime=0;
            CombatChanneling.hit(context,()->((com.matuvent.mineturn.mixin.TridentHitAccess)trident).mineturn$hit(new net.minecraft.world.phys.EntityHitResult(context.target())));
            context.source().playSound(net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),1,1);
        }finally{trident.discard();context.target().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
    }
}
