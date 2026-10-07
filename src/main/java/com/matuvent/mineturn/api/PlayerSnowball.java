package com.matuvent.mineturn.api;

public final class PlayerSnowball implements CombatEffects.Effect {
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action a){
        if(a.self() || a.consume()!=1 || a.ranged()==null || a.ranged().ammoCount()!=0 || PotionTargets.ground(a))
            throw new IllegalArgumentException("player_snowball requires enemy ranged target, consume=1 and no extra ammunition");
    }
    @Override public String validate(CombatEffects.Context c){
        return c.source() instanceof net.minecraft.server.level.ServerPlayer && c.item().is(net.minecraft.world.item.Items.SNOWBALL)
                ?VanillaCombatItems.slotError(c):"需要玩家使用雪球。";
    }
    @Override public void execute(CombatEffects.Context c){
        var level=(net.minecraft.server.level.ServerLevel)c.source().level();
        var ball=new net.minecraft.world.entity.projectile.Snowball(level,c.source());
        try{
            ball.setItem(c.item().copyWithCount(1));ball.setPos(c.target().getBoundingBox().getCenter());
            c.target().invulnerableTime=0;
            ((com.matuvent.mineturn.mixin.SnowballHitAccess)ball).mineturn$hit(new net.minecraft.world.phys.EntityHitResult(c.target()));
            c.target().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            level.playSound(null,c.source(),net.minecraft.sounds.SoundEvents.SNOWBALL_THROW,net.minecraft.sounds.SoundSource.PLAYERS,0.5f,1);
            ((net.minecraft.server.level.ServerPlayer)c.source()).awardStat(net.minecraft.stats.Stats.ITEM_USED.get(net.minecraft.world.item.Items.SNOWBALL));
        }finally{ball.discard();}
    }
}
