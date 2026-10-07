package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded six-neighbour 3D search, with swept checks and replayed control-area history. */
final class SpatialRoutes {
    private record Key(long x,long y,long z) {
        static Key of(Vec3 p){return new Key(Math.round(p.x*1024),Math.round(p.y*1024),Math.round(p.z*1024));}
    }
    private record Node(Vec3 point,double cost,double score,List<Vec3> samples,long order){}
    private static final List<Vec3> DIRECTIONS=List.of(new Vec3(1,0,0),new Vec3(-1,0,0),
            new Vec3(0,1,0),new Vec3(0,-1,0),new Vec3(0,0,1),new Vec3(0,0,-1));
    static TerrainPath.Result find(LivingEntity entity,Vec3 goal,double limit,String mode,Supplier<Predicate<Vec3>> controls) {
        var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score).thenComparingLong(Node::order));
        var best=new HashMap<Key,Double>();long order=0;
        open.add(new Node(entity.position(),0,MovementDistance.between(entity.position(),goal),List.of(),order++));
        best.put(Key.of(entity.position()),0.0);
        for(int expanded=0;!open.isEmpty() && expanded<(mode.equals("climbing")?2048:512);expanded++) {
            var node=open.remove();
            if(node.cost()>best.getOrDefault(Key.of(node.point()),Double.POSITIVE_INFINITY)+1e-6)continue;
            if(node.cost()+MovementDistance.between(node.point(),goal)<=limit+1e-6) {
                var finish=edge(entity,node,goal,mode,controls);
                if(finish!=null)return new TerrainPath.Result(goal,node.cost()+MovementDistance.between(node.point(),goal),List.of(),finish);
            }
            for(var direction:DIRECTIONS) {
                double step=mode.equals("climbing")?0.5:1;
                Vec3 point=node.point().add(direction.scale(step));double cost=node.cost()+step,score=cost+MovementDistance.between(point,goal);
                var key=Key.of(point);
                if(score>limit+1e-6 || cost>=best.getOrDefault(key,Double.POSITIVE_INFINITY)-1e-6)continue;
                var samples=edge(entity,node,point,mode,controls);if(samples==null)continue;
                best.put(key,cost);open.add(new Node(point,cost,score,samples,order++));
            }
        }
        return null;
    }
    private static List<Vec3> edge(LivingEntity entity,Node node,Vec3 end,String mode,Supplier<Predicate<Vec3>> controls) {
        var control=controls.get();
        for(var sample:node.samples())if(!control.test(sample))return null;
        var samples=new ArrayList<>(node.samples());Vec3 previous=node.point();
        int steps=Math.max(1,(int)Math.ceil(previous.distanceTo(end)/0.1));
        for(int i=1;i<=steps;i++) {
            Vec3 point=node.point().lerp(end,i/(double)steps);
            boolean clear=mode.equals("climbing")?ClimbingPath.canStep(entity,previous,point):mode.equals("aquatic")?AquaticPath.canStep(entity,previous,point)
                    :SpatialPath.canStep(entity,previous,point,mode.equals("swimming"),mode.equals("phasing"));
            if(!clear || !control.test(point))return null;
            samples.add(point);previous=point;
        }
        return samples;
    }
    static TerrainPath.Result pursue(LivingEntity entity,Vec3 goal,double budget,String mode,Supplier<Predicate<Vec3>> controls) {
        var route=find(entity,goal,Math.min(40,budget+12),mode,controls);
        if(route==null || route.cost()<=budget+1e-6)return route;
        Vec3 previous=entity.position();double cost=0;var samples=new ArrayList<Vec3>();
        for(var point:route.samples()) {
            double step=MovementDistance.between(previous,point);if(cost+step>budget+1e-6)break;
            samples.add(point);cost+=step;previous=point;
        }
        return cost<0.01?null:new TerrainPath.Result(previous,cost,List.of(),samples);
    }
    private SpatialRoutes(){}
}
