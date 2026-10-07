package com.matuvent.mineturn.mixin;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConduitBlockEntity.class)
public interface ConduitAuraAccess {
    @Invoker("applyEffects")
    static void mineturn$apply(Level level,BlockPos pos,List<BlockPos> frame){throw new AssertionError();}
}
