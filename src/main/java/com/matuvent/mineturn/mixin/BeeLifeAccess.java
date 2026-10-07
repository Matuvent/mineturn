package com.matuvent.mineturn.mixin;
import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Bee.class)
public interface BeeLifeAccess {
    @Accessor("timeSinceSting") int mineturn$stingAge();
    @Accessor("timeSinceSting") void mineturn$stingAge(int value);
    @Accessor("underWaterTicks") int mineturn$wetTicks();
    @Accessor("underWaterTicks") void mineturn$wetTicks(int value);
}
