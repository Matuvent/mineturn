package com.matuvent.mineturn.network;

/**
 * Ordering and preemption rules for action performances, kept free of client state so they can be
 * exercised by server-side GameTests. The client owns one running performance plus at most one
 * pending slot:
 *
 * <ul>
 *   <li>a strictly higher priority interrupts the running performance immediately;</li>
 *   <li>an equal priority replaces it (the newest action is the one worth showing);</li>
 *   <li>a lower priority waits in the single pending slot instead of fighting for the camera.</li>
 * </ul>
 *
 * <p>Arrivals are deduplicated by a per-battle monotonic sequence, so replayed or out-of-order packets
 * never restart an animation.
 */
public final class ActionAnimationOrder {
    public enum Action { PLAY, QUEUE, DROP }

    /** Performance priority levels shared by the server that tags them and the client that sorts them. */
    public static final int PRIORITY_EAT = 50;
    public static final int PRIORITY_GENERIC = 100;
    public static final int PRIORITY_ATTACK = 200;
    private long lastSequence = -1;
    private boolean running;
    private int runningPriority;
    private boolean hasPending;
    private int pendingPriority;
    private long pendingSequence;

    /** Records a new arrival and decides what the client should do with it. */
    public Action arrive(long sequence, int priority) {
        if (sequence <= lastSequence) return Action.DROP;
        lastSequence = sequence;
        if (!running) {
            running = true;
            runningPriority = priority;
            return Action.PLAY;
        }
        if (priority >= runningPriority) {
            runningPriority = priority;
            return Action.PLAY;
        }
        if (!hasPending || priority > pendingPriority) {
            hasPending = true;
            pendingPriority = priority;
            pendingSequence = sequence;
        }
        return Action.QUEUE;
    }

    /** The running performance finished; returns true when the caller should start the pending one. */
    public boolean finishRunning() {
        running = false;
        if (!hasPending) return false;
        hasPending = false;
        running = true;
        runningPriority = pendingPriority;
        lastSequence = Math.max(lastSequence, pendingSequence);
        return true;
    }

    /** Drops everything, e.g. on battle exit or a world change. */
    public void clear() {
        lastSequence = -1;
        running = false;
        runningPriority = 0;
        hasPending = false;
        pendingPriority = 0;
        pendingSequence = 0;
    }

    public boolean running() { return running; }
    public int runningPriority() { return runningPriority; }
    public boolean hasPending() { return hasPending; }
    public int pendingPriority() { return pendingPriority; }
}
