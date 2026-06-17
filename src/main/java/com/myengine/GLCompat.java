package com.myengine;

import static org.lwjgl.opengl.GL11.glLineWidth;

/**
 * Cross-platform compatibility shims for OpenGL Core-profile divergences.
 *
 * Why this exists:
 *   glLineWidth(x) for x != 1.0 is technically illegal in Core profile
 *   (deprecated in GL 3.0, removed-for-real in Core). Apple's GL driver
 *   strictly enforces this and raises GL_INVALID_VALUE on any wide-line
 *   call, which then stalls the context (the next frame never renders;
 *   editor goes gray, play mode freezes on frame 1). NVIDIA / AMD drivers
 *   on Windows + Linux ignore the spec and silently honor wide aliased
 *   lines anyway — the engine has always relied on that to draw the
 *   selection gizmo's thick X / Y / Z handles and to make hovered axes
 *   visibly thicker than non-hovered.
 *
 *   Approach: pass the requested width through verbatim on Windows /
 *   Linux (preserving the gizmo's thick hover feedback the user is used
 *   to) and clamp to 1.0 ONLY on macOS where the wide call is fatal.
 *   We previously tried querying GL_ALIASED_LINE_WIDTH_RANGE and clamping
 *   to the driver-reported range; that broke Windows because Core-profile
 *   drivers correctly report [1.0, 1.0] even when they actually accept
 *   wider values in practice.
 *
 * Use {@link #lineWidth(float)} instead of glLineWidth() throughout the
 * codebase so future platform-specific quirks have a single place to live.
 */
class GLCompat {

    // True on macOS — where wide aliased lines crash the GL context.
    // Everywhere else we trust the driver to do the right thing.
    private static final boolean STRICT_LINE_WIDTH =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    static void lineWidth(float w) {
        if (STRICT_LINE_WIDTH && w > 1.0f) {
            glLineWidth(1.0f);
        } else {
            glLineWidth(w);
        }
    }
}
