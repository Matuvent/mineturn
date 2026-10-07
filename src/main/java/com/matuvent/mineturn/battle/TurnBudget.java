package com.matuvent.mineturn.battle;

/** Spendable movement and independently counted main/bonus actions. Sprint doubles unspent movement. */
public final class TurnBudget {
    private double remaining;
    private int mainActions=1;
    private int bonusActions=1;
    private boolean disengaged;
    private long activity;
    public long activity(){return activity;}
    public TurnBudget(double distance) { remaining = Math.max(0, distance); }
    public void limitMovement(double maximum) { remaining=Math.min(remaining,Math.max(0,maximum)); }
    public double remaining() { return remaining; }
    public boolean canMove() { return remaining >= 0.01; }
    public boolean canAct() { return mainActions>0; }
    public int mainActions(){return mainActions;}
    public int bonusActions(){return bonusActions;}
    public void setActions(int main,int bonus){
        if(main<0 || main>100 || bonus<0 || bonus>100)throw new IllegalArgumentException("Action counts must be between 0 and 100");
        mainActions=main;bonusActions=bonus;
    }
    public boolean canPay(com.matuvent.mineturn.data.ActionCost cost){return mainActions>=cost.main() && bonusActions>=cost.bonus();}
    public void require(com.matuvent.mineturn.data.ActionCost cost){
        if(!canPay(cost))throw new IllegalArgumentException("需要 "+cost.main()+" 次主要行动、"+cost.bonus()+" 次次要行动；当前剩余 "+mainActions+" / "+bonusActions+"。");
    }
    public void spend(com.matuvent.mineturn.data.ActionCost cost){
        require(cost);mainActions-=cost.main();bonusActions-=cost.bonus();activity++;
    }
    public void bonusAct(){
        if(bonusActions<=0)throw new IllegalStateException("Bonus actions exhausted");
        bonusActions--;activity++;
    }
    public boolean disengaged() { return disengaged; }
    public void move(double distance) {
        if (!Double.isFinite(distance) || distance <= 0 || distance > remaining + 1e-6) throw new IllegalStateException("移动距离不足。");
        remaining = Math.max(0, remaining - distance);
        activity++;
    }
    public void sprint() {
        if (!canMove()) throw new IllegalStateException("没有剩余移动距离。");
        act(); remaining *= 2;
    }
    public void act() {
        if (mainActions<=0) throw new IllegalStateException("Main actions exhausted");
        mainActions--;activity++;
    }
    public void disengage() { act(); disengaged = true; }
}
