package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/** Resolves a successful timing check as one arrow hit, without spawning a real-time projectile. */
public final class CombatProjectiles {
    /** Pay each launched projectile through native durability; retain only lanes actually launched. */
    public static void payCrossbowDurability(LivingEntity shooter,ItemStack live,ItemStack snapshot,ItemStack ammo) {
        payCrossbowDurability(shooter,live,snapshot,ammo,net.minecraft.world.entity.EquipmentSlot.MAINHAND);
    }
    private static void payCrossbowDurability(LivingEntity shooter,ItemStack live,ItemStack snapshot,ItemStack ammo,net.minecraft.world.entity.EquipmentSlot slot) {
        var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
        var loaded=snapshot.getOrDefault(component,net.minecraft.world.item.component.ChargedProjectiles.EMPTY);
        int planned=loaded.isEmpty()?EnchantmentHelper.processProjectileCount((ServerLevel)shooter.level(),snapshot,shooter,1):loaded.getItems().size();
        // Keep the existing one/three lane limit for custom projectile-count enchantments.
        planned=loaded.isEmpty()?(planned>1?3:1):Math.min(3,planned);
        var launched=new java.util.ArrayList<ItemStack>();
        for(int i=0;i<planned && !live.isEmpty();i++) {
            launched.add(ammo.copyWithCount(1));
            live.hurtAndBreak(ammo.is(Items.FIREWORK_ROCKET)?3:1,shooter,slot);
        }
        snapshot.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(launched));
    }
    public static double cooldown(LivingEntity source,ItemStack weapon,CombatData.Action action) {
        if(!weapon.is(Items.CROSSBOW) || !java.util.Set.of("mineturn:projectile","mineturn:firework","mineturn:mob_crossbow","mineturn:pillager_charge").contains(action.effect()))return action.cooldown();
        double ratio=EnchantmentHelper.modifyCrossbowChargingTime(weapon,source,1.25f)/1.25;
        return action.cooldown()*Math.clamp(ratio,0,1);
    }
    public static boolean preservesArrow(LivingEntity source,ItemStack weapon,CombatData.Action action) {
        if(source.hasInfiniteMaterials())return true;
        if(!action.effect().equals("mineturn:projectile") || action.ranged()==null
                || !action.ranged().ammunition().equals("minecraft:arrow"))return false;
        return weapon.is(Items.BOW) && EnchantmentHelper.getItemEnchantmentLevel(
                source.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.INFINITY),weapon)>0;
    }
    static void execute(CombatEffects.Context context) {
        var source=context.source();
        ServerLevel level=(ServerLevel)source.level();
        // The constructor runs native projectile-spawn enchantments, including Flame.
        ItemStack ammo=context.battle().ammunition();
        if(ammo.isEmpty())ammo=new ItemStack(Items.ARROW);
        AbstractArrow arrow=((net.minecraft.world.item.ArrowItem)ammo.getItem()).createArrow(level,ammo,source,context.item().isEmpty()?null:context.item());
        try {
            var resolved=targets(context,Math.min(16,Math.max(0,arrow.getPierceLevel())));
            CombatVisuals.arrows(context,resolved,ammo);
            for(var target:resolved)if(target.isAlive())impact(context,arrow,target);
        }finally{arrow.discard();}
    }
    static java.util.Set<LivingEntity> targets(CombatEffects.Context context,int pierce) {
            var source=context.source();var level=(ServerLevel)source.level();
            var targets=new java.util.LinkedHashSet<LivingEntity>();targets.add(context.target());
            if(context.item().is(Items.CROSSBOW)) {
                Vec3 origin=source.getEyePosition(),direction=context.target().getEyePosition().subtract(origin).normalize();
                double selectedDistance=origin.distanceTo(context.target().getEyePosition());
                double reach=Math.min(256,selectedDistance+context.action().range());
                if(pierce>0)collect(context,origin,direction,reach,selectedDistance+0.01,pierce,targets);
                var loaded=context.item().getOrDefault(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
                        net.minecraft.world.item.component.ChargedProjectiles.EMPTY);
                int count=loaded.isEmpty()?EnchantmentHelper.processProjectileCount(level,context.item(),source,1):loaded.getItems().size();
                if(count>1) {
                    // A single timing check resolves the three vanilla crossbow lanes; never stack hits on one target.
                    collect(context,origin,direction.yRot((float)Math.toRadians(10)),reach,0,1+pierce,targets);
                    if(count>2)collect(context,origin,direction.yRot((float)Math.toRadians(-10)),reach,0,1+pierce,targets);
                }
            }
            return targets;
    }
    private static void collect(CombatEffects.Context context,Vec3 origin,Vec3 direction,double reach,double minimum,int count,
                                java.util.Set<LivingEntity> targets) {
        Vec3 end=origin.add(direction.scale(reach));
        var block=context.source().level().clip(new net.minecraft.world.level.ClipContext(origin,end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,context.source()));
        double stop=block.getType()==net.minecraft.world.phys.HitResult.Type.MISS?reach:origin.distanceTo(block.getLocation());
        record Hit(LivingEntity entity,double distance){}
        context.battle().enemies().stream().filter(e->e!=context.target() && e.isAlive())
                .map(e->new Hit(e,e.getBoundingBox().inflate(0.1).clip(origin,end).map(origin::distanceTo).orElse(Double.POSITIVE_INFINITY)))
                .filter(hit->hit.distance()>=minimum && hit.distance()<stop)
                .sorted(java.util.Comparator.comparingDouble(Hit::distance).thenComparingInt(hit->hit.entity().getId()))
                .limit(count).forEach(hit->targets.add(hit.entity()));
    }
    public static ItemStack mobCrossbow(LivingEntity source) {
        if(source.getMainHandItem().is(Items.CROSSBOW))return source.getMainHandItem();
        return source.getOffhandItem().is(Items.CROSSBOW)?source.getOffhandItem():ItemStack.EMPTY;
    }
    /** Snapshot before breakage so the last launched arrow retains its weapon enchantments. */
    public static ItemStack prepareMobCrossbow(LivingEntity source) {
        var live=mobCrossbow(source);if(live.isEmpty())return ItemStack.EMPTY;
        var weapon=live.copy();
        payCrossbowDurability(source,live,weapon,new ItemStack(Items.ARROW),
                live==source.getMainHandItem()?net.minecraft.world.entity.EquipmentSlot.MAINHAND:net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        return weapon;
    }
    static void crossbow(CombatEffects.Context context) {
        var source=context.source();var weapon=prepareMobCrossbow(source);if(weapon.isEmpty())return;
        var level=(ServerLevel)source.level();
        var arrow=((net.minecraft.world.item.ArrowItem)Items.ARROW).createArrow(level,new ItemStack(Items.ARROW),source,weapon);
        var shot=new CombatEffects.Context(source,context.target(),weapon,context.action(),context.battleTime(),context.battle());
        try {
            var resolved=targets(shot,Math.min(16,Math.max(0,arrow.getPierceLevel())));
            CombatVisuals.arrows(shot,resolved,new ItemStack(Items.ARROW));
            for(var target:resolved)
                if(target.isAlive())impact(shot,arrow,target,(float)arrow.getBaseDamage(),1.6f);
            source.playSound(net.minecraft.sounds.SoundEvents.CROSSBOW_SHOOT,1,1);
        }finally {arrow.discard();}
    }
    static ItemStack skeletonBow(LivingEntity source) {
        if(source.getMainHandItem().is(Items.BOW))return source.getMainHandItem();
        return source.getOffhandItem().is(Items.BOW)?source.getOffhandItem():ItemStack.EMPTY;
    }
    static void skeleton(CombatEffects.Context context) {
        var skeleton=(net.minecraft.world.entity.monster.AbstractSkeleton)context.source();
        ItemStack bow=skeletonBow(skeleton);if(bow.isEmpty())return;
        ItemStack ammo=skeleton.getProjectile(bow);
        AbstractArrow arrow=((com.matuvent.mineturn.mixin.SkeletonArrowAccess)skeleton).mineturn$arrow(ammo,1,bow);
        try {
            CombatVisuals.flight((ServerLevel)skeleton.level(),skeleton.getEyePosition(),context.target().getEyePosition(),ammo,true,false);
            impact(new CombatEffects.Context(skeleton,context.target(),bow,context.action(),context.battleTime(),context.battle()),arrow,context.target(),(float)arrow.getBaseDamage(),1.6f);
            skeleton.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT,1,1);
        }finally{arrow.discard();}
    }
    private static void impact(CombatEffects.Context context,AbstractArrow arrow,LivingEntity target) {
        impact(context,arrow,target,(float)(context.action().amount()/3),3);
    }
    private static void impact(CombatEffects.Context context,AbstractArrow arrow,LivingEntity target,float base,float speed) {
            var source=context.source();var level=(ServerLevel)source.level();
            var damageSource=source.damageSources().arrow(arrow,source);
            // Vanilla full-draw arrow speed is 3; apply Power to base damage before speed scaling.
            float damage=(float)Math.ceil(speed*EnchantmentHelper.modifyDamage(level,context.item(),target,damageSource,base));
            int oldFire=target.getRemainingFireTicks();
            if(arrow.isOnFire())target.igniteForSeconds(5);
            target.invulnerableTime=0;
            if(target.hurt(damageSource,damage)) {
                EnchantmentHelper.doPostAttackEffectsWithItemSource(level,target,damageSource,context.item());
                if(target.isAlive())((com.matuvent.mineturn.mixin.ArrowEffectsAccess)arrow).mineturn$hitEffects(target);
            }
            else target.setRemainingFireTicks(oldFire);
            target.setDeltaMovement(Vec3.ZERO);
    }
    private CombatProjectiles(){}
}
