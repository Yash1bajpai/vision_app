package com.vision.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A validated, bounded, ordered sequence of actions proposed by a reasoning provider.
 *
 * Safety invariants:
 * - Bounded: at most {@link #MAX_ACTIONS} steps; constructing a longer plan throws.
 * - Immutable: the step list is an unmodifiable copy; steps are never reordered or added.
 * - Approval isolation: the plan carries no approval semantics — every step flows through
 *   the same risk policy and modal confirmation as a single typed command. Approval of
 *   one step never approves a later step.
 */
public final class VisionPlan {
    public static final int MAX_ACTIONS = 3;

    public final String request;
    private final List<VisionAction> steps;

    public VisionPlan(String request, List<VisionAction> steps) {
        this.request = request != null ? request : "";
        List<VisionAction> copy = new ArrayList<>();
        if (steps != null) {
            for (VisionAction step : steps) {
                if (step != null) {
                    copy.add(step);
                }
            }
        }
        if (copy.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("plan exceeds MAX_ACTIONS (" + MAX_ACTIONS + ")");
        }
        this.steps = Collections.unmodifiableList(copy);
    }

    public int stepCount() {
        return steps.size();
    }

    public VisionAction step(int index) {
        return steps.get(index);
    }

    public List<VisionAction> steps() {
        return steps;
    }
}
