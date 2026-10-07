package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded A* on ground surfaces; each edge reuses the same swept collision and control rules as direct movement. */
final class GroundRoutes {
    private record Key(long x,long y,long z) {
        static Key of(Vec3 p){return new Key(Math.round(p.x*1024),Math.round(p.y*1024),Math.round(p.z*1024));}
    }
    private record Node(Vec3 point,double cost,double score,List<TerrainPath.Landing> landings,List<Vec3> samples,long order){}
    static TerrainPath.Result find(LivingEntity entity,Vec3 goal,double budget,Supplier<Predicate<Vec3>> controlFactory) {
        var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score).thenComparingLong(Node::order));
        var best=new HashMap<Key,Double>();long order=0;
        open.add(new Node(entity.position(),0,horizontal(entity.position(),goal),List.of(),List.of(),order++));
        best.put(Key.of(entity.position()),0.0);
        int expanded=0;
        while(!open.isEmpty() && expanded++<512) {
            var node=open.remove();
            if(node.cost()>best.getOrDefault(Key.of(node.point()),Double.POSITIVE_INFINITY)+1e-6)continue;
            double remaining=budget-node.cost();
            if(horizontal(node.point(),goal)<=remaining+1e-6) {
                var finish=edge(entity,node,goal.subtract(node.point()).multiply(1,0,1),controlFactory);
                if(finish.destination().distanceToSqr(goal)<0.0025 && finish.cost()<=remaining+1e-6)
                    return combine(node,finish);
            }
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(dx==0 && dz==0)continue;
                double length=(Math.abs(dx)+Math.abs(dz));
                if(length>remaining+1e-6)continue;
                var step=edge(entity,node,new Vec3(dx,0,dz),controlFactory);
                if(Math.abs(step.cost()-length)>1e-5)continue;
                double cost=node.cost()+length,estimate=cost+horizontal(step.destination(),goal);
                if(estimate>budget+1e-6)continue;
                var key=Key.of(step.destination());
                if(cost>=best.getOrDefault(key,Double.POSITIVE_INFINITY)-1e-6)continue;
                best.put(key,cost);var route=combine(node,step);
                open.add(new Node(step.destination(),cost,estimate,route.landings(),route.samples(),order++));
            }
        }
        return null;
    }
    private static TerrainPath.Result edge(LivingEntity entity,Node node,Vec3 offset,Supplier<Predicate<Vec3>> factory) {
        var control=factory.get();
        for(var point:node.samples())if(!control.test(point))return new TerrainPath.Result(node.point(),0,List.of());
        return TerrainPath.traceFrom(entity,node.point(),offset,control);
    }
    /** Search beyond this turn, then keep only the affordable prefix ending on a supported surface. */
    static TerrainPath.Result pursue(LivingEntity entity,Vec3 goal,double budget,Supplier<Predicate<Vec3>> controlFactory) {
        var route=find(entity,goal,Math.min(40,budget+12),controlFactory);
        if(route==null || route.cost()<=budget+1e-6)return route;
        Vec3 previous=entity.position();double cost=0,acceptedCost=0;int accepted=0;
        var landings=new ArrayList<TerrainPath.Landing>();int landing=0,acceptedLandings=0;
        for(int i=0;i<route.samples().size();i++) {
            Vec3 point=route.samples().get(i);
            cost+=horizontal(previous,point);if(cost>budget+1e-6)break;
            if(landing<route.landings().size() && point.distanceToSqr(route.landings().get(landing).position())<1e-8)
                landings.add(route.landings().get(landing++));
            var body=entity.getBoundingBox().move(point.subtract(entity.position())).deflate(1e-6);
            if(!entity.level().noCollision(entity,body.move(0,-0.06,0))) {
                accepted=i+1;acceptedCost=cost;acceptedLandings=landings.size();
            }
            previous=point;
        }
        if(accepted==0 || acceptedCost<0.01)return null;
        return new TerrainPath.Result(route.samples().get(accepted-1),acceptedCost,landings.subList(0,acceptedLandings),route.samples().subList(0,accepted));
    }
    private static TerrainPath.Result combine(Node node,TerrainPath.Result edge) {
        var landings=new ArrayList<>(node.landings());landings.addAll(edge.landings());
        var samples=new ArrayList<>(node.samples());samples.addAll(edge.samples());
        return new TerrainPath.Result(edge.destination(),node.cost()+edge.cost(),landings,samples);
    }
    private static double horizontal(Vec3 a,Vec3 b){return MovementDistance.ground(a.subtract(b));}
    private GroundRoutes(){}
}
