package com.matuvent.mineturn.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** S2C presentation only. No corresponding server request or entity. */
public record ProjectileVisual(ResourceLocation dimension,Vec3 from,Vec3 to,ItemStack item,int ticks,boolean hit,boolean firework) implements CustomPacketPayload {
    public static final Type<ProjectileVisual> TYPE=new Type<>(ResourceLocation.parse("mineturn:projectile_visual"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ProjectileVisual> CODEC=StreamCodec.of((b,v)->{
        b.writeResourceLocation(v.dimension);point(b,v.from);point(b,v.to);ItemStack.STREAM_CODEC.encode(b,v.item);b.writeVarInt(v.ticks);b.writeBoolean(v.hit);b.writeBoolean(v.firework);
    },b->new ProjectileVisual(b.readResourceLocation(),point(b),point(b),ItemStack.STREAM_CODEC.decode(b),b.readVarInt(),b.readBoolean(),b.readBoolean()));
    public ProjectileVisual {item=item.copyWithCount(1);}
    @Override public ItemStack item(){return item.copy();}
    public boolean valid(){return finite(from)&&finite(to)&&from.distanceToSqr(to)<=256*256 && !item.isEmpty() && ticks>=1 && ticks<=40;}
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private static void point(RegistryFriendlyByteBuf b,Vec3 p){b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);}
    private static Vec3 point(RegistryFriendlyByteBuf b){return new Vec3(b.readDouble(),b.readDouble(),b.readDouble());}
    @Override public Type<ProjectileVisual> type(){return TYPE;}
}
