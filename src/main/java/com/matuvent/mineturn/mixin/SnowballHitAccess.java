package com.matuvent.mineturn.mixin;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Snowball.class)
public interface SnowballHitAccess {
    @Invoker("onHitEntity") void mineturn$hit(EntityHitResult hit);
}
