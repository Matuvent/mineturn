package com.matuvent.mineturn.battle;

/** During a single move, entering a control area permits continued approach, but never departure or crossing. */
public final class ApproachGate {
    private boolean entered;
    private double lastGap = Double.POSITIVE_INFINITY;
    public boolean accept(double gap, boolean inside) {
        if (entered && (!inside || gap > lastGap + 1e-5)) return false;
        if (inside) { entered = true; lastGap = gap; }
        return true;
    }
}
