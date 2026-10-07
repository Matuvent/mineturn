package com.matuvent.mineturn.battle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.function.ToDoubleFunction;

/** Deterministic action-distance clock. Insertion order breaks ties. */
public final class Timeline<T> {
    public static final double LAP = 10000;
    private final Map<T, Double> remaining = new LinkedHashMap<>();
    private double time;
    public void add(T actor) { remaining.putIfAbsent(actor, LAP); }
    public void remove(T actor) { remaining.remove(actor); }
    public double time() { return time; }
    public double remaining(T actor) { return remaining.getOrDefault(actor, LAP); }
    public double until(T actor, ToDoubleFunction<T> agility) { return remaining(actor) / agility.applyAsDouble(actor); }
    public double nextDelay(ToDoubleFunction<T> agility) {
        return remaining.keySet().stream().mapToDouble(actor -> until(actor, agility)).min().orElse(Double.POSITIVE_INFINITY);
    }
    public void advance(double elapsed, ToDoubleFunction<T> agility) {
        if (!Double.isFinite(elapsed) || elapsed < 0 || elapsed > nextDelay(agility) + 1e-7) throw new IllegalArgumentException("Invalid AV advance");
        remaining.replaceAll((actor, distance) -> Math.max(0, distance - agility.applyAsDouble(actor) * elapsed));
        time += elapsed;
    }
    public record Upcoming<T>(T actor, double inAv) {}
    /** Forecast on a copy, keeping the live clock and partial progress untouched. */
    public List<Upcoming<T>> forecast(int count, ToDoubleFunction<T> agility) {
        Timeline<T> copy = new Timeline<>();
        copy.remaining.putAll(remaining);
        List<Upcoming<T>> result = new ArrayList<>();
        for (int i = 0; i < count && !copy.remaining.isEmpty(); i++) {
            T actor = copy.next(agility);
            result.add(new Upcoming<>(actor, copy.time()));
        }
        return List.copyOf(result);
    }
    public T next(ToDoubleFunction<T> agility) {
        if (remaining.isEmpty()) throw new IllegalStateException("Empty timeline");
        T selected = null;
        double delta = Double.POSITIVE_INFINITY;
        Map<T, Double> speeds = new LinkedHashMap<>();
        for (var entry : remaining.entrySet()) {
            double speed = agility.applyAsDouble(entry.getKey());
            if (!Double.isFinite(speed) || speed <= 0) throw new IllegalArgumentException("Invalid agility");
            speeds.put(entry.getKey(), speed);
            double wait = Math.max(0, entry.getValue()) / speed;
            if (selected == null || wait < delta - 1e-9) { delta = wait; selected = entry.getKey(); }
        }
        final double elapsed = delta;
        remaining.replaceAll((actor, distance) -> Math.max(0, distance - speeds.get(actor) * elapsed));
        time += elapsed;
        remaining.put(selected, LAP);
        return selected;
    }
}
