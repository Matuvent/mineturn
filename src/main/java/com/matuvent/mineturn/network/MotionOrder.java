package com.matuvent.mineturn.network;

/** Client ordering guard, shared with server-side codec/order tests; reset on battle/world changes. */
public final class MotionOrder {
    private long latest=-1;
    public boolean acceptMotion(long sequence){
        if(sequence<0 || sequence<=latest)return false;
        latest=sequence;return true;
    }
    public boolean acceptSnapshot(long sequence){
        if(sequence<0 || sequence<latest)return false;
        latest=sequence;return true;
    }
    public void reset(){latest=-1;}
}
