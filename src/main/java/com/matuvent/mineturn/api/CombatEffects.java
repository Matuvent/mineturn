package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Register during mod setup, before datapack loading. Callbacks run on the server thread. */
public final class CombatEffects {
    public interface BattleAccess {
        /** Enemy-only cylindrical field, prepaid; callback runs once per valid target each pulse. */
        default String previewField(CombatFields.Request request){return "Fields unavailable";}
        default CombatFields.Result createField(CombatFields.Request request,java.util.function.Consumer<Context> onTarget){throw new UnsupportedOperationException("Fields unavailable");}
        default boolean removeField(java.util.UUID id){throw new UnsupportedOperationException("Fields unavailable");}
        /** Pure preflight, null when legal; does not create an entity or fire spawn events. */
        default String previewSummon(CombatSummons.Request request){return "Summons unavailable";}
        /** Paid effect only; rechecks and spawns one managed unit, with no second payment. */
        default CombatSummons.Result summon(CombatSummons.Request request){throw new UnsupportedOperationException("Summons unavailable");}
        /** Explicit position target, or null for ordinary entity-targeted actions. */
        default net.minecraft.world.phys.Vec3 impactPosition(){return null;}
        /** Read-only preflight for a living participant; no events, movement or resource changes. */
        default CombatTeleport.Result previewTeleport(LivingEntity target,net.minecraft.world.phys.Vec3 destination,CombatTeleport.Rules rules){return CombatTeleport.Result.rejected("Teleport unavailable");}
        /** Paid effect/managed callback only. Revalidates; never charges a second action or movement budget. */
        default CombatTeleport.Result teleportTo(LivingEntity target,net.minecraft.world.phys.Vec3 destination,CombatTeleport.Rules rules){throw new UnsupportedOperationException("Teleport unavailable");}
        default boolean chorusReady(){return false;}
        default void chorusTeleport(){throw new UnsupportedOperationException();}
        default boolean blazeCharged(){return false;}
        default boolean canChargeBlaze(){return false;}
        default void blazeCharge(boolean value){throw new UnsupportedOperationException();}
        default void fireball(boolean large){throw new UnsupportedOperationException();}
        default boolean canTeleport(double range){return false;}
        default void teleport(double range){throw new UnsupportedOperationException();}
        default boolean canLaunchBullet(){return false;}
        default void launchBullet(){throw new UnsupportedOperationException();}
        default boolean canGuard(){return true;}
        default boolean canChargeCrossbow(){return false;}
        default void chargeCrossbow(double av){throw new UnsupportedOperationException();}
        default void endCrossbowCharge(){throw new UnsupportedOperationException();}
        default void disableShield(double av){throw new UnsupportedOperationException();}
        default boolean canSummonVex(){return false;}
        default void summonVex(){throw new UnsupportedOperationException();}
        default void fangs(boolean circle){throw new UnsupportedOperationException();}
        default void explodeCreeper(){throw new UnsupportedOperationException("Explosion unavailable");}
        default boolean canSummonSilverfish(){return false;}
        default void summonSilverfish(){throw new UnsupportedOperationException("Summoning unavailable");}
        default boolean canPrepareDive(){return false;}
        default void prepareDive(){throw new UnsupportedOperationException();}
        default boolean canDive(){return false;}
        default void dive(){throw new UnsupportedOperationException("Dive unavailable");}
        default void guard(){throw new UnsupportedOperationException("Guard unavailable");}
        default boolean canLeap(){return false;}
        default void leap(){throw new UnsupportedOperationException("Leap unavailable");}
        default void guardianBeam(double durationAv){throw new UnsupportedOperationException("Beam unavailable");}
        default void endGuardianBeam(){throw new UnsupportedOperationException("Beam unavailable");}
        /** Adjust this actor's remaining action counts; only inside a committed effect. */
        default void changeActions(int mainDelta,int bonusDelta){throw new UnsupportedOperationException("Action counts unavailable");}
        default void spendBonusAction(){throw new UnsupportedOperationException("Bonus actions unavailable");}
        /** Forced displacement ignores engagement/budget, but respects collision, chunks and landing damage. */
        default boolean displace(LivingEntity target,net.minecraft.world.phys.Vec3 offset){throw new UnsupportedOperationException("Displacement unavailable");}
        java.util.List<LivingEntity> enemies();
        default int lingeringCount(){return 0;}
        default void lingering(net.minecraft.world.item.alchemy.PotionContents potion){throw new UnsupportedOperationException("Lingering clouds unavailable");}
        /** Current battle participants, including allies. */
        default java.util.List<LivingEntity> participants(){return enemies();}
        /** Copy of ammunition paid for at launch, including its components. */
        default ItemStack ammunition(){return ItemStack.EMPTY;}
        /** Only usable inside execute or a managed delayed callback; targets must remain in this battle. */
        void hurt(LivingEntity target, float amount);
        /** Prepaid continuation, cancelled when caster/target leaves or the battle closes. No new action is granted. */
        void after(double delayAv, java.util.function.Consumer<Context> continuation);
        /** Pure capacity/delay preflight; shared with AI timers (128 pending per battle). */
        default boolean canSchedule(double delayAv){return false;}
        /** Prepaid continuation with a fresh, read-only settlement predicate. No refund if rejected. */
        default java.util.UUID afterChecked(double delayAv,java.util.function.Predicate<Context> check,java.util.function.Consumer<Context> continuation){throw new UnsupportedOperationException("Checked timeline unavailable");}
        /** Cancel only this caster's pending ticket, inside a committed effect/callback. No refund. */
        default boolean cancelScheduled(java.util.UUID ticket){throw new UnsupportedOperationException("Timeline cancellation unavailable");}

    }
    public record Context(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, double battleTime, BattleAccess battle) {}
    public interface Effect {
        /** Reload-time validation of custom parameters. Throw IllegalArgumentException for invalid definitions. */
        default void validateDefinition(CombatData.Action action) {}
        /** Pure preflight. Return an error message, or null if execution is valid. */
        default String validate(Context context) { return null; }
        /** Called once, after the main action is committed, inside the damage authorization scope. */
        void execute(Context context);
    }
    private static final Map<String, Effect> TYPES = new ConcurrentHashMap<>();
    static {
        RaidEffects.register();
        RealmEffects.register();
        CompanionEffects.register();
        RepairEffects.register();
        register(ResourceLocation.parse("mineturn:wind_burst"),new WindBurst(true));
        register(ResourceLocation.parse("mineturn:breeze_burst"),new WindBurst(false));
        register(ResourceLocation.parse("mineturn:phantom_telegraph"),new Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,false);}
            public String validate(Context c){return c.battle().canPrepareDive()?null:"无法锁定安全航线或仍在恢复。";}
            public void execute(Context c){c.battle().prepareDive();}
        });
        register(ResourceLocation.parse("mineturn:creeper_explode"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){if(action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("creeper_explode requires an enemy melee action");}
            @Override public String validate(Context context){return context.source() instanceof net.minecraft.world.entity.monster.Creeper && context.battle()!=null?null:"只有苦力怕可以自爆。";}
            @Override public void execute(Context context){context.battle().explodeCreeper();}
        });
        register(ResourceLocation.parse("mineturn:silverfish_summon"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(!action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("silverfish_summon requires a self action");
                for(String key:java.util.List.of("search_radius","max_summons"))if(action.parameters().has(key)) {
                    double value=action.parameters().get(key).getAsDouble();
                    if(!Double.isFinite(value) || value!=Math.floor(value) || value<1 || value>8)throw new IllegalArgumentException(key+" must be an integer in 1..8");
                }
            }
            @Override public String validate(Context context){return context.battle()!=null && context.battle().canSummonSilverfish()?null:"附近没有可唤醒的虫蚀方块，或战斗人数已满。";}
            @Override public void execute(Context context){context.battle().summonSilverfish();}
        });
        register(ResourceLocation.parse("mineturn:phantom_dive"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){if(action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("phantom_dive requires melee enemy target");}
            @Override public String validate(Context context){return context.source() instanceof net.minecraft.world.entity.monster.Phantom
                    && context.battle()!=null && context.battle().canDive()?null:"俯冲需要高度优势、足够移动距离和安全路线。";}
            @Override public void execute(Context context){context.battle().dive();}
        });
        register(ResourceLocation.parse("mineturn:shield_guard"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){if(!action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("shield_guard requires self target");}
            @Override public String validate(Context context){return context.source() instanceof ServerPlayer && context.item().is(net.minecraft.world.item.Items.SHIELD)
                    && context.battle().canGuard()?null:"需要使用快捷栏或副手中的盾牌，且盾牌不能处于冷却中。";}
            @Override public void execute(Context context){context.battle().guard();}
        });
        register(ResourceLocation.parse("mineturn:spider_leap"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("spider_leap requires melee enemy target");
            }
            @Override public String validate(Context context){return context.source() instanceof net.minecraft.world.entity.monster.Spider
                    && context.battle()!=null && context.battle().canLeap()?null:"没有安全的跳扑路线或移动距离不足。";}
            @Override public void execute(Context context){context.battle().leap();}
        });
        register(ResourceLocation.parse("mineturn:guardian_beam"),new GuardianBeam());
        register(ResourceLocation.parse("mineturn:player_trident"),new PlayerTrident());
        register(ResourceLocation.parse("mineturn:riptide"),new Riptide());
        register(ResourceLocation.parse("mineturn:ender_pearl"),new EnderPearl());
        register(ResourceLocation.parse("mineturn:chorus_fruit"),new ChorusFruit());
        register(ResourceLocation.parse("mineturn:player_snowball"),new PlayerSnowball());
        register(ResourceLocation.parse("mineturn:teleport_offset"),new TeleportOffset());
        register(ResourceLocation.parse("mineturn:repulse"),new Repulse());
        register(ResourceLocation.parse("mineturn:delayed_strike"),new DelayedStrike());
        register(ResourceLocation.parse("mineturn:summon"),new SummonEffect());
        register(ResourceLocation.parse("mineturn:hazard_field"),new HazardField());
        register(ResourceLocation.parse("mineturn:drowned_trident"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.consume()!=0 || action.ranged()==null || action.ranged().ammoCount()!=0)
                    throw new IllegalArgumentException("drowned_trident requires ranged enemy target and no inventory consumption");
            }
            @Override public String validate(Context context){
                return !(context.source() instanceof net.minecraft.world.entity.monster.Drowned)
                        || !context.source().getMainHandItem().is(net.minecraft.world.item.Items.TRIDENT)?"溺尸需要主手持三叉戟。"
                        :context.source().distanceTo(context.target())>context.action().range()?"目标超出射程。":null;
            }
            @Override public void execute(Context context){
                // Vanilla Drowned creates a fresh, unenchanted trident and retains its equipped weapon.
                var trident=new net.minecraft.world.entity.projectile.ThrownTrident(context.source().level(),context.source(),new ItemStack(net.minecraft.world.item.Items.TRIDENT));
                try {
                    context.target().invulnerableTime=0;
                    ((com.matuvent.mineturn.mixin.TridentHitAccess)trident).mineturn$hit(new net.minecraft.world.phys.EntityHitResult(context.target()));
                    context.source().playSound(net.minecraft.sounds.SoundEvents.DROWNED_SHOOT,1,1);
                }finally{trident.discard();context.target().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
            }
        });
        register(ResourceLocation.parse("mineturn:laser"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.consume()!=0)throw new IllegalArgumentException("laser requires enemy target and consume=0");
            }
            @Override public String validate(Context context){
                return context.source().distanceTo(context.target())>context.action().range()?"目标超出激光射程。":null;
            }
            @Override public void execute(Context context){
                if(validate(context)!=null)return;
                var start=context.source().getEyePosition();var end=context.target().getBoundingBox().getCenter();
                int count=Math.min(128,Math.max(1,(int)Math.ceil(start.distanceTo(end)*6)));
                var level=(net.minecraft.server.level.ServerLevel)context.source().level();
                for(int i=0;i<=count;i++){
                    var point=start.lerp(end,i/(double)count);
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,point.x,point.y,point.z,1,0,0,0,0);
                }
                context.battle().hurt(context.target(),(float)context.action().amount());
            }
        });
        register(ResourceLocation.parse("mineturn:slime_melee"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.ranged()!=null || action.consume()!=0)throw new IllegalArgumentException("slime_melee requires enemy melee target and consume=0");
            }
            @Override public String validate(Context context){
                return context.source() instanceof net.minecraft.world.entity.monster.Slime slime
                        && (slime.getSize()>1 || slime instanceof net.minecraft.world.entity.monster.MagmaCube)?null:"小型史莱姆不能造成接触伤害。";
            }
            @Override public void execute(Context context){
                if(validate(context)!=null)return;
                var mob=context.source();var target=context.target();var damage=mob.damageSources().mobAttack(mob);
                target.invulnerableTime=0;
                try {
                    if(target.hurt(damage,((com.matuvent.mineturn.mixin.SlimeAttackAccess)mob).mineturn$attackDamage())) {
                        net.minecraft.world.item.enchantment.EnchantmentHelper.doPostAttackEffects((net.minecraft.server.level.ServerLevel)mob.level(),target,damage);
                        mob.playSound(net.minecraft.sounds.SoundEvents.SLIME_ATTACK,1,1);
                    }
                }finally{target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
            }
        });
        register(ResourceLocation.parse("mineturn:mob_crossbow"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.consume()!=0 || action.ranged()==null || action.ranged().ammoCount()!=0)
                    throw new IllegalArgumentException("mob_crossbow requires ranged enemy target with consume=0 and ammo_count=0");
            }
            @Override public String validate(Context context){
                return !(context.source() instanceof net.minecraft.world.entity.monster.Pillager
                        || context.source() instanceof net.minecraft.world.entity.monster.piglin.Piglin)
                        || CombatProjectiles.mobCrossbow(context.source()).isEmpty()?"生物需要持弩。"
                        :context.source().distanceTo(context.target())>context.action().range()?"目标超出射程。":null;
            }
            @Override public void execute(Context context){CombatProjectiles.crossbow(context);}
        });
        register(ResourceLocation.parse("mineturn:skeleton_arrow"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.consume()!=0 || action.ranged()==null || action.ranged().ammoCount()!=0)
                    throw new IllegalArgumentException("skeleton_arrow requires ranged enemy target, consume=0 and ammo_count=0");
            }
            @Override public String validate(Context context){
                return !(context.source() instanceof net.minecraft.world.entity.monster.AbstractSkeleton)
                        || CombatProjectiles.skeletonBow(context.source()).isEmpty()?"骷髅需要持弓。"
                        :context.source().distanceTo(context.target())>context.action().range()?"目标超出射程。":null;
            }
            @Override public void execute(Context context){CombatProjectiles.skeleton(context);}
        });
        register(ResourceLocation.parse("mineturn:firework"),new CombatFireworks());
        register(ResourceLocation.parse("mineturn:splash"),new CombatSplash(false));
        register(ResourceLocation.parse("mineturn:lingering"),new CombatSplash(true));
        register(ResourceLocation.parse("mineturn:projectile"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){if(action.ranged()==null || !java.util.Set.of("minecraft:arrow","minecraft:tipped_arrow","minecraft:spectral_arrow").contains(action.ranged().ammunition()))throw new IllegalArgumentException("projectile requires ranged arrow ammunition");}
            @Override public void execute(Context context){CombatProjectiles.execute(context);}
        });
        register(ResourceLocation.parse("mineturn:weapon_melee"),new Effect(){
            @Override public String validate(Context context){return context.source() instanceof ServerPlayer ? VanillaCombatItems.slotError(context) : null;}
            @Override public void execute(Context context){VanillaCombatItems.melee(context);}
        });
        register(ResourceLocation.parse("mineturn:native_food"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(!action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("native_food requires self, consume=0 and no ranged check");
            }
            @Override public String validate(Context context){
                String error=VanillaCombatItems.slotError(context);
                return error!=null?error:context.item().getFoodProperties(context.source())==null || context.item().is(net.minecraft.world.item.Items.CHORUS_FRUIT)?"该食物尚未适配战斗。":null;
            }
            @Override public void execute(Context context){VanillaCombatItems.drink(context);}
        });
        register(ResourceLocation.parse("mineturn:species_melee"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(action.self() || action.ranged()!=null || action.consume()!=0)throw new IllegalArgumentException("species_melee requires melee enemy target and consume=0");
            }
            @Override public String validate(Context context){
                return context.source() instanceof net.minecraft.world.entity.monster.Spider
                        || context.source() instanceof net.minecraft.world.entity.monster.WitherSkeleton
                        || context.source() instanceof net.minecraft.world.entity.monster.Husk
                        || context.source() instanceof net.minecraft.world.entity.animal.IronGolem
                        || context.source() instanceof net.minecraft.world.entity.monster.Ravager
                        || context.source() instanceof net.minecraft.world.entity.monster.Zoglin
                        || context.source() instanceof net.minecraft.world.entity.monster.hoglin.Hoglin?null:"此生物尚未适配原版特殊近战。";
            }
            @Override public void execute(Context context){
                var mob=(net.minecraft.world.entity.Mob)context.source();var target=context.target();
                ((com.matuvent.mineturn.mixin.StatusAccess)mob).mineturn$equipment();
                target.invulnerableTime=0;
                try{
                    if(mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin || mob instanceof net.minecraft.world.entity.monster.Zoglin) {
                        // Reuse native damage without HoglinAi broadcasting new targets outside this battle.
                        mob.level().broadcastEntityEvent(mob,(byte)4);
                        mob.playSound(mob instanceof net.minecraft.world.entity.monster.Zoglin?net.minecraft.sounds.SoundEvents.ZOGLIN_ATTACK:net.minecraft.sounds.SoundEvents.HOGLIN_ATTACK,1,1);
                        net.minecraft.world.entity.monster.hoglin.HoglinBase.hurtAndThrowTarget(mob,target);
                    }else mob.doHurtTarget(target);
                }
                finally{mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
            }
        });
        register(ResourceLocation.parse("mineturn:mob_melee"),new Effect(){
            @Override public String validate(Context context){return context.source().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)==null?"生物缺少攻击力属性。":null;}
            @Override public void execute(Context context){VanillaCombatItems.mobMelee(context);}
        });
        register(ResourceLocation.parse("mineturn:drink"),new Effect(){
            @Override public void validateDefinition(CombatData.Action action){
                if(!action.self() || action.consume()!=0 || action.ranged()!=null)throw new IllegalArgumentException("drink requires self target, consume=0 and no ranged check");
            }
            @Override public String validate(Context context){
                String invalid=VanillaCombatItems.slotError(context);
                return invalid!=null?invalid:VanillaCombatItems.drinkable(context.item())?null:"仅支持饮用药水或牛奶。";
            }
            @Override public void execute(Context context){VanillaCombatItems.drink(context);}
        });
        register(ResourceLocation.parse("mineturn:burst"), new Effect() {
            @Override public void validateDefinition(CombatData.Action action) {
                parameter(action, "radius", 3, 0.1, 16);
                parameter(action, "delay_av", 0, 0, 100000);
            }
            @Override public void execute(Context context) {
                double delay = parameter(context.action(), "delay_av", 0, 0, 100000);
                if (delay > 0) context.battle().after(delay, this::impact); else impact(context);
            }
            private void impact(Context context) {
                double radius = parameter(context.action(), "radius", 3, 0.1, 16);
                for (var enemy : context.battle().enemies())
                    if (enemy.distanceToSqr(context.target()) <= radius * radius && context.target().hasLineOfSight(enemy))
                        context.battle().hurt(enemy, (float) context.action().amount());
            }
        });
        register(ResourceLocation.parse("mineturn:damage"), context -> {
            var source = context.source();
            context.target().invulnerableTime = 0;
            context.target().hurt(source instanceof ServerPlayer player ? source.damageSources().playerAttack(player)
                    : source.damageSources().mobAttack(source), (float) context.action().amount());
            if (source instanceof ServerPlayer && context.action().ranged() == null && context.item().isDamageableItem())
                context.item().hurtAndBreak(1, source, EquipmentSlot.MAINHAND);
        });
        register(ResourceLocation.parse("mineturn:heal"), context -> context.target().heal((float) context.action().amount()));
        register(ResourceLocation.parse("mineturn:food"), new Effect() {
            @Override public String validate(Context context) {
                var food = context.item().getFoodProperties(context.source());
                return context.source() instanceof ServerPlayer player && food != null
                        && context.action().self() && context.action().consume() == 1 ? null : "现在不能食用该物品。";
            }
            @Override public void execute(Context context) {
                VanillaCombatItems.food(context);
            }
        });
    }
    private CombatEffects() {}
    public static double parameter(CombatData.Action action, String name, double fallback, double min, double max) {
        var json = action.parameters();
        double value = json.has(name) ? json.get(name).getAsDouble() : fallback;
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid action parameter " + name);
        return value;
    }
    public static void register(ResourceLocation id, Effect effect) {
        if (TYPES.putIfAbsent(id.toString(), effect) != null) throw new IllegalArgumentException("Duplicate effect " + id);
    }
    public static boolean contains(String id) { return TYPES.containsKey(id); }
    public static Effect get(String id) {
        Effect effect = TYPES.get(id);
        if (effect == null) throw new IllegalArgumentException("Unknown effect " + id);
        return effect;
    }
}
