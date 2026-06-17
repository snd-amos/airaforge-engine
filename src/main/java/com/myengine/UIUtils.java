package com.myengine;

import imgui.ImGui;

/**
 * Global precision wrapper for ImGui float editors (Phase 5D).
 *
 * <p>Use these instead of {@code ImGui.sliderFloat} / {@code ImGui.dragFloat}
 * everywhere in the engine's UI so two mechanics are applied universally
 * (see the "Global UI rule" in CLAUDE.md):
 * <ul>
 *   <li><b>RMB-slowdown</b> — while Right-Click is held during a drag, the
 *       {@code v_speed} drops to {@code 0.001} for fine control (else the
 *       caller's default speed).</li>
 *   <li><b>SHIFT-snap</b> — releasing/editing with SHIFT down rounds the value
 *       to the nearest {@code 0.5} increment, matching the 3D gizmo.</li>
 * </ul>
 * CTRL+Click still types an exact value (standard {@code dragFloat} behaviour),
 * which is the other half of why these are drags, not sliders.
 */
final class UIUtils {
    private UIUtils() {}

    private static float speed(float defaultSpeed) {
        return ImGui.getIO().getMouseDown(1) ? 0.001f : defaultSpeed;
    }

    private static float snap(float v) {
        return Math.round(v * 2f) / 2f;
    }

    /** Single-float drag with RMB-slowdown + SHIFT-snap. */
    static boolean dragFloat(String label, float[] v, float defaultSpeed) {
        boolean changed = ImGui.dragFloat(label, v, speed(defaultSpeed));
        if (ImGui.isItemEdited() && ImGui.getIO().getKeyShift()) v[0] = snap(v[0]);
        return changed;
    }

    /** Single-float drag with min/max clamp (for the old ranged sliders). */
    static boolean dragFloat(String label, float[] v, float defaultSpeed, float min, float max) {
        boolean changed = ImGui.dragFloat(label, v, speed(defaultSpeed), min, max);
        if (ImGui.isItemEdited() && ImGui.getIO().getKeyShift()) {
            v[0] = Math.max(min, Math.min(max, snap(v[0])));
        }
        return changed;
    }

    /** Vec3 drag with RMB-slowdown + SHIFT-snap (all three components). */
    static boolean dragFloat3(String label, float[] v, float defaultSpeed) {
        boolean changed = ImGui.dragFloat3(label, v, speed(defaultSpeed));
        if (ImGui.isItemEdited() && ImGui.getIO().getKeyShift()) {
            for (int i = 0; i < 3; i++) v[i] = snap(v[i]);
        }
        return changed;
    }
}
