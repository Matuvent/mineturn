package com.matuvent.mineturn;

import com.matuvent.mineturn.battle.BattleManager;
import com.matuvent.mineturn.data.CombatData;
import com.matuvent.mineturn.data.CombatItem;
import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(MineTurn.MODID)
public final class MineTurn {
    public static final String MODID = "mineturn";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITIES=DeferredRegister.create(Registries.ENTITY_TYPE,MODID);
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>,net.minecraft.world.entity.EntityType<com.matuvent.mineturn.battle.BattleBullet>> BATTLE_BULLET=ENTITIES.register("shulker_bullet_unit",
            ()->net.minecraft.world.entity.EntityType.Builder.of(com.matuvent.mineturn.battle.BattleBullet::new,net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.3f,0.3f).noSave().noSummon().clientTrackingRange(8).updateInterval(1).build("mineturn:shulker_bullet_unit"));
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>,net.minecraft.world.entity.EntityType<com.matuvent.mineturn.battle.BattleDevice>> BATTLE_DEVICE=ENTITIES.register("field_core",
            ()->net.minecraft.world.entity.EntityType.Builder.of(com.matuvent.mineturn.battle.BattleDevice::new,net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.75f,0.75f).noSave().noSummon().clientTrackingRange(8).build("mineturn:field_core"));
    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, MODID);
    public static final DeferredHolder<Attribute, Attribute> AGILITY = ATTRIBUTES.register("agility",
            () -> new RangedAttribute("attribute.mineturn.agility", 100, 1, 1000).setSyncable(true));
    public static final DeferredHolder<Attribute,Attribute> MAIN_ACTIONS=ATTRIBUTES.register("main_actions",()->new RangedAttribute("attribute.mineturn.main_actions",1,0,100).setSyncable(true));
    public static final DeferredHolder<Attribute,Attribute> BONUS_ACTIONS=ATTRIBUTES.register("bonus_actions",()->new RangedAttribute("attribute.mineturn.bonus_actions",1,0,100).setSyncable(true));
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CombatItem>> COMBAT =
            COMPONENTS.registerComponentType("combat", builder -> builder.persistent(CombatItem.CODEC));

    public MineTurn(IEventBus bus) {
        ENTITIES.register(bus);
        bus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event)->event.put(BATTLE_BULLET.get(),com.matuvent.mineturn.battle.BattleBullet.attributes().build()));
        bus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event)->event.put(BATTLE_DEVICE.get(),com.matuvent.mineturn.battle.BattleDevice.attributes().build()));
        ATTRIBUTES.register(bus);
        COMPONENTS.register(bus);
        bus.addListener(com.matuvent.mineturn.network.BattleNetwork::register);
        bus.addListener((EntityAttributeModificationEvent event) -> event.getTypes().forEach(type -> {event.add(type, AGILITY);event.add(type,MAIN_ACTIONS);event.add(type,BONUS_ACTIONS);}));
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new CombatData(event.getServerResources())));
        NeoForge.EVENT_BUS.register(BattleManager.class);
    }
}
