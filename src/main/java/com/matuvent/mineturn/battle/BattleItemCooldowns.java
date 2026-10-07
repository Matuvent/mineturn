package com.matuvent.mineturn.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** One conversion rule for supported native item clocks. Deadlines are absolute battle AV. */
final class BattleItemCooldowns {
    private static double inherit(BattleSession battle,ServerPlayer player,Item item){
        int ticks=((com.matuvent.mineturn.api.RemainingItemCooldown)player.getCooldowns()).mineturn$remaining(item);
        return ticks>0?battle.clock.time()+ticks*BattleStatus.AV_PER_TICK:0;
    }
    static void enter(BattleSession battle,ServerPlayer player){
        var m=battle.member(player);
        m.shieldDisabledUntil=inherit(battle,player,Items.SHIELD);
        m.pearlUntil=inherit(battle,player,Items.ENDER_PEARL);
        m.chorusUntil=inherit(battle,player,Items.CHORUS_FRUIT);
        m.windUntil=inherit(battle,player,Items.WIND_CHARGE);
    }
    static double sync(BattleSession battle,BattleSession.Member member,Item item,double until){
        if(until<=0 || !(member.entity instanceof ServerPlayer player))return until;
        int ticks=(int)Math.ceil(Math.max(0,until-battle.clock.time())/BattleStatus.AV_PER_TICK);
        if(ticks>0){player.getCooldowns().addCooldown(item,ticks);return until;}
        player.getCooldowns().removeCooldown(item);return 0;
    }
    static void clock(BattleSession battle,BattleSession.Member m){
        m.shieldDisabledUntil=sync(battle,m,Items.SHIELD,m.shieldDisabledUntil);
        m.pearlUntil=sync(battle,m,Items.ENDER_PEARL,m.pearlUntil);
        m.chorusUntil=sync(battle,m,Items.CHORUS_FRUIT,m.chorusUntil);
        m.windUntil=sync(battle,m,Items.WIND_CHARGE,m.windUntil);
    }
    private BattleItemCooldowns(){}
}
