package com.matuvent.mineturn.mixin;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(SmallFireball.class)
public interface SmallFireballAccess {
    @Invoker("onHitEntity") void mineturn$hit(EntityHitResult hit);
}
