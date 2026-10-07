package com.matuvent.mineturn.data;

import com.google.gson.JsonElement;

/** Shared turn cost for items, grants and both AI entry points. */
public record ActionCost(int main,int bonus) {
    public ActionCost {
        if(main<0 || main>100 || bonus<0 || bonus>100 || main+bonus==0)
            throw new IllegalArgumentException("action_cost requires 0..100 of each action and at least one action");
    }
    public static ActionCost parse(JsonElement value) {
        if(value==null)return new ActionCost(1,0);
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())return switch(value.getAsString()) {
            case "main" -> new ActionCost(1,0);
            case "bonus" -> new ActionCost(0,1);
            default -> throw new IllegalArgumentException("Unknown action_cost");
        };
        if(!value.isJsonObject())throw new IllegalArgumentException("action_cost must be main, bonus or an object");
        var object=value.getAsJsonObject();
        for(var key:object.keySet())if(!key.equals("main") && !key.equals("bonus"))throw new IllegalArgumentException("Unknown action_cost field: "+key);
        return new ActionCost(count(object.get("main")),count(object.get("bonus")));
    }
    private static int count(JsonElement value) {
        if(value==null)return 0;
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Action count must be an integer");
        try{return value.getAsBigDecimal().intValueExact();}
        catch(ArithmeticException|NumberFormatException error){throw new IllegalArgumentException("Action count must be an integer",error);}
    }
}
