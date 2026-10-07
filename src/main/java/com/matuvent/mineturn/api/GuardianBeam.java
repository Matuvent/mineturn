package com.matuvent.mineturn.api;

import com.matuvent.mineturn.battle.BattleStatus;
import com.matuvent.mineturn.data.CombatData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;

/** Native guardian damage sequence, charged on the managed AV clock. */
final class GuardianBeam implements CombatEffects.Effect {
    @Override public void validateDefinition(CombatData.Action action){
        if(action.self() || action.ranged()!=null || action.consume()!=0)throw new IllegalArgumentException("guardian_beam requires enemy target, consume=0 and no timing check");
    }
    @Override public String validate(CombatEffects.Context context){
        return !(context.source() instanceof Guardian)?"只有守卫者可以使用光束。"
                :((Guardian)context.source()).hasActiveAttackTarget()?"正在引导光束。"
                :!context.source().hasLineOfSight(context.target()) || context.source().distanceTo(context.target())>context.action().range()?"光束目标超出射程或被遮挡。":null;
    }
    @Override public void execute(CombatEffects.Context context){
        var guardian=(Guardian)context.source();
        particles(context);
        context.battle().guardianBeam(guardian.getAttackDuration()*BattleStatus.AV_PER_TICK);
        context.battle().after(guardian.getAttackDuration()*BattleStatus.AV_PER_TICK,this::impact);
    }
    private void impact(CombatEffects.Context context){
        var guardian=(Guardian)context.source();var target=context.target();
        boolean active=guardian.hasActiveAttackTarget();
        context.battle().endGuardianBeam();
        if(!active || validate(context)!=null)return;
        particles(context);
        float magic=1+(guardian.level().getDifficulty()==Difficulty.HARD?2:0)+(guardian instanceof ElderGuardian?2:0);
        ((com.matuvent.mineturn.mixin.StatusAccess)guardian).mineturn$equipment();
        target.invulnerableTime=0;
        try {
            target.hurt(guardian.damageSources().indirectMagic(guardian,guardian),magic);
            if(target.isAlive())guardian.doHurtTarget(target);
        }finally{target.setDeltaMovement(Vec3.ZERO);guardian.setDeltaMovement(Vec3.ZERO);}
    }
    private static void particles(CombatEffects.Context context){
        var start=context.source().getEyePosition();var end=context.target().getEyePosition();
        int steps=Math.max(1,Math.min(64,(int)(start.distanceTo(end)*4)));
        var level=(ServerLevel)context.source().level();
        for(int i=0;i<=steps;i++){
            var point=start.lerp(end,i/(double)steps);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.BUBBLE,point.x,point.y,point.z,1,0,0,0,0);
        }
    }
}
