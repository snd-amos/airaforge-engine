package com.myengine;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.type.ImBoolean;

/**
 * Dedicated Character Editor viewport — Phase 1 (rendering foundation only).
 *
 * <p>An ImGui panel that displays a SEPARATE offscreen Vulkan render target
 * ({@link VkOffscreenTarget}) as a texture, isolated from the main Scene
 * Viewport, so characters can later be posed / edited on their own. For now it
 * just shows the target's clear colour (a dark blueprint grey) — no models,
 * physics, IK or animation yet.
 *
 * <p>Each frame the panel reports its available content-region size via
 * {@link #wantWidth} / {@link #wantHeight}; {@code VulkanMain} reads those to
 * resize the offscreen target and record its clear pass before the ImGui
 * composite samples it.
 */
final class CharacterEditorWindow {

    /** Last content-region size requested by the panel — read by VulkanMain to
     *  resize the offscreen target. Seeded to a sane default for frame 0. */
    int wantWidth  = 512;
    int wantHeight = 512;

    private final ImBoolean open = new ImBoolean(true);

    // Phase 5 — visual debug toggles. Read by VulkanMain's PASS 2b to draw the
    // collision capsule / skeleton over the preview.
    private final ImBoolean showCollision = new ImBoolean(false);
    private final ImBoolean showBones     = new ImBoolean(false);
    // True while the user is click-dragging inside the preview image to orbit
    // it (tracked across frames so a fast drag that leaves the image keeps
    // rotating until the mouse button is released).
    private boolean dragging = false;

    // Phase 6A — animation slot text buffers, re-synced when the bound loco
    // changes (new preview dropped).
    private CharacterLocomotionComponent lastAnimLoco;
    private final imgui.type.ImString animIdleBuf   = new imgui.type.ImString(256);
    private final imgui.type.ImString animWalkBuf   = new imgui.type.ImString(256);
    private final imgui.type.ImString animSprintBuf = new imgui.type.ImString(256);
    private final imgui.type.ImString animJumpBuf   = new imgui.type.ImString(256);

    /** Render the panel. {@code textureId} is the ImGui handle for the
     *  offscreen colour image (0 until registered). {@code onModelDropped}
     *  is fired with a model path when an asset is dragged onto the viewport.
     *  {@code loco} is the live locomotion component of the loaded preview
     *  character (null until one is dropped) — edited by the sliders.
     *  {@code onPreviewRotate} receives {@code {dxPixels, dyPixels}} drag
     *  deltas to rotate the preview character (Phase 5). {@code onPreviewZoom}
     *  receives the mouse-wheel delta for CTRL+scroll camera zoom (Phase 5B).
     *  {@code previewTransform} is the live preview character's transform (null
     *  until a model is dropped) — edited by the Base Scale / Rotation sliders.
     *  {@code onPreviewPan} receives RMB-drag deltas to pan the camera (Phase 5C).
     *  {@code onTextureDropped} applies a dropped texture to the preview mesh,
     *  {@code onUniformScale} applies a uniform Base Scale with feet-pivot math
     *  (both Phase 5F). */
    void render(long textureId, java.util.function.Consumer<String> onModelDropped,
                CharacterLocomotionComponent loco, Runnable onSetActivePlayer,
                java.util.function.Consumer<float[]> onPreviewRotate,
                java.util.function.Consumer<Float> onPreviewZoom,
                Transform previewTransform,
                java.util.function.Consumer<float[]> onPreviewPan,
                java.util.function.Consumer<String> onTextureDropped,
                java.util.function.Consumer<Float> onUniformScale,
                Runnable onAssignAnim,
                java.util.function.Consumer<String> onPlayAnim,
                Runnable onClearCharacter) {
        if (!open.get()) return;
        ImGui.begin("Character Editor", open);

        // Phase 6B — Clear Character: reset to a blank editor.
        if (ImGui.button("X Clear Character") && onClearCharacter != null) {
            onClearCharacter.run();
        }

        // Reserve a footer for the Locomotion panel so the image leaves room
        // below it. (One frame-line is always visible; expanded sliders scroll.)
        // Phase 6B (Issue 3) — cap the image at half the available height so
        // the settings below always have guaranteed scroll space (resizing the
        // panel vertically no longer pushes them off-screen).
        ImVec2 avail = ImGui.getContentRegionAvail();
        int w = (int) avail.x;
        int h = (int) Math.min(avail.y * 0.5f, avail.y - ImGui.getFrameHeightWithSpacing());
        if (w > 0 && h > 0) { wantWidth = w; wantHeight = h; }

        // Sample the offscreen colour image (same straight 0,0–1,1 UVs as the
        // Scene Viewport — the negative-height offscreen pass already orients
        // world-up to the top of the texture).
        if (textureId != 0 && w > 0 && h > 0) {
            ImGui.image(textureId, w, h, 0, 0, 1, 1);
            boolean imgHovered = ImGui.isItemHovered();
            // Drag-and-drop TARGET on the viewport image: accept a model path
            // dragged from the Asset Browser (payload type "CHAR_MODEL", the
            // same payload the Asset Browser model chips publish).
            if (ImGui.beginDragDropTarget()) {
                Object model = ImGui.acceptDragDropPayload("CHAR_MODEL");
                if (model instanceof String) {
                    onModelDropped.accept((String) model);
                }
                // Phase 5F — also accept a TEXTURE payload to re-skin the
                // preview character before spawning.
                Object tex = ImGui.acceptDragDropPayload("TEXTURE");
                if (tex instanceof String && onTextureDropped != null) {
                    onTextureDropped.accept((String) tex);
                }
                ImGui.endDragDropTarget();
            }

            // Phase 5 — click-drag inside the preview to orbit the character.
            // (The image is only a drag-drop TARGET, never a SOURCE, so a plain
            // LMB drag carries no payload and is free to use for rotation.)
            if (imgHovered && ImGui.isMouseClicked(0)) dragging = true;
            if (!ImGui.isMouseDown(0)) dragging = false;
            if (dragging && ImGui.isMouseDragging(0) && onPreviewRotate != null) {
                ImVec2 d = ImGui.getMouseDragDelta(0);
                if (d.x != 0 || d.y != 0) {
                    onPreviewRotate.accept(new float[]{ d.x, d.y });
                    ImGui.resetMouseDragDelta(0);   // delta is incremental per frame
                }
            }

            // Phase 5B — CTRL + mouse wheel zooms the preview camera.
            if (imgHovered && ImGui.getIO().getKeyCtrl() && onPreviewZoom != null) {
                float scroll = ImGui.getIO().getMouseWheel();
                if (scroll != 0) onPreviewZoom.accept(scroll);
            }

            // Phase 5C — RIGHT-click drag pans the preview camera target.
            if (imgHovered && ImGui.isMouseDragging(1) && onPreviewPan != null) {
                ImVec2 pd = ImGui.getMouseDragDelta(1);
                if (pd.x != 0 || pd.y != 0) {
                    onPreviewPan.accept(new float[]{ pd.x, pd.y });
                    ImGui.resetMouseDragDelta(1);
                }
            }
        }

        // Locomotion Attributes panel (Phase 3A) — wired live to the dropped
        // preview character's CharacterLocomotionComponent.
        if (loco == null) {
            ImGui.textDisabled("Drag a model here to edit its locomotion.");
        } else if (ImGui.collapsingHeader("Locomotion Attributes")) {
            loco.collisionRadius     = slider("Collision Radius",      loco.collisionRadius,     0.05f, 2.0f);
            loco.collisionHalfHeight = slider("Collision Half-Height",  loco.collisionHalfHeight, 0.2f,  3.0f);
            loco.collisionOffsetY    = slider("Collision Offset Y",     loco.collisionOffsetY,   -2.0f,  2.0f);
            loco.walkSpeed           = slider("Walk Speed",             loco.walkSpeed,           0.0f,  20.0f);
            loco.sprintSpeed         = slider("Sprint Speed",           loco.sprintSpeed,         0.0f,  40.0f);
            loco.jumpVelocity        = slider("Jump Velocity",          loco.jumpVelocity,        0.0f,  30.0f);
            loco.stepHeight          = slider("Step Height",            loco.stepHeight,          0.0f,  2.0f);
            // Phase 3B — first-person integration. (Red Dot = the eye-height
            // marker drawn in the preview at this Y, Phase 5C.)
            loco.cameraHeightOffset  = slider("Camera Height (Red Dot)", loco.cameraHeightOffset,  0.0f,  3.0f);
            boolean headless = loco.hiddenSubmeshIndex >= 0;
            ImBoolean hl = new ImBoolean(headless);
            if (ImGui.checkbox("Hide Avatar in First-Person", hl)) {
                loco.hiddenSubmeshIndex = hl.get() ? 0 : -1;
            }
        }

        // Phase 6A — animation state slots (Idle / Walk / Sprint / Jump). Drop
        // a model/anim file from the Asset Browser (CHAR_MODEL payload) onto a
        // slot, or type a path. Assigning any slot plays Idle on the preview.
        if (loco != null) {
            if (loco != lastAnimLoco) {   // new preview → re-sync the buffers
                lastAnimLoco = loco;
                animIdleBuf.set(nz(loco.animIdle));
                animWalkBuf.set(nz(loco.animWalk));
                animSprintBuf.set(nz(loco.animSprint));
                animJumpBuf.set(nz(loco.animJump));
            }
            if (ImGui.collapsingHeader("Animations")) {
                loco.animIdle   = animSlot("Idle##anim",   animIdleBuf,   loco.animIdle,   onAssignAnim, onPlayAnim);
                loco.animWalk   = animSlot("Walk##anim",   animWalkBuf,   loco.animWalk,   onAssignAnim, onPlayAnim);
                loco.animSprint = animSlot("Sprint##anim", animSprintBuf, loco.animSprint, onAssignAnim, onPlayAnim);
                loco.animJump   = animSlot("Jump##anim",   animJumpBuf,   loco.animJump,   onAssignAnim, onPlayAnim);
            }
        }

        // Phase 5D — full 3D Base transforms. The main Inspector is locked to
        // Position for the active player, so the raw previewCharacter is
        // permanently positioned / oriented / sized here instead (fixes
        // poorly-imported meshes). All via the precision dragFloat wrapper.
        if (previewTransform != null) {
            ImGui.separator();
            ImGui.text("Base Transform");
            float[] bp = { previewTransform.x, previewTransform.y, previewTransform.z };
            if (UIUtils.dragFloat3("Base Position", bp, 0.05f)) {
                previewTransform.x = bp[0]; previewTransform.y = bp[1]; previewTransform.z = bp[2];
            }
            float[] br = { previewTransform.rotX, previewTransform.rotY, previewTransform.rotZ };
            if (UIUtils.dragFloat3("Base Rotation", br, 1.0f)) {
                previewTransform.rotX = br[0]; previewTransform.rotY = br[1]; previewTransform.rotZ = br[2];
            }
            // Phase 5F — single UNIFORM Base Scale. The handler applies it to
            // x/y/z and adjusts Base Position Y so the feet (mesh bottom) stay
            // at world Y=0 — the character scales upward, never clipping the
            // floor / reference cube.
            float[] bsv = { previewTransform.scaleX };
            if (UIUtils.dragFloat("Base Scale (uniform)", bsv, 0.05f) && onUniformScale != null) {
                onUniformScale.accept(bsv[0]);
            }
        }

        // Phase 5 — visual debug toggles (rendered in VulkanMain's PASS 2b).
        ImGui.separator();
        ImGui.checkbox("Show Collision Bounds", showCollision);
        ImGui.checkbox("Show Skeleton/Bones", showBones);

        // Phase 4 — spawn this character into the live world and bind its
        // locomotion to the active player controller. Disabled until a
        // character is loaded (loco == null means no preview).
        ImGui.separator();
        boolean hasCharacter = (loco != null);
        if (!hasCharacter) ImGui.beginDisabled();
        if (ImGui.button("Set as Active Player") && onSetActivePlayer != null) {
            onSetActivePlayer.run();
        }
        if (!hasCharacter) ImGui.endDisabled();

        ImGui.end();
    }

    /** Ranged float editor — now a precision dragFloat (UIUtils) so RMB-
     *  slowdown + SHIFT-snap + CTRL-click-to-type apply (Phase 5D). */
    private static float slider(String label, float value, float min, float max) {
        float[] v = { value };
        UIUtils.dragFloat(label, v, 0.05f, min, max);
        return v[0];
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Phase 6A/6B — one animation slot: a small Play [>] button + a text field
     *  with a CHAR_MODEL drag-drop target. Returns the (possibly updated) path,
     *  fires {@code onAssign} when the value changes, and {@code onPlay} with
     *  the path when the Play button is clicked. */
    private static String animSlot(String label, imgui.type.ImString buf,
                                   String current, Runnable onAssign,
                                   java.util.function.Consumer<String> onPlay) {
        String result = current;
        // Play button forces this specific clip onto the preview.
        if (ImGui.button(">##play_" + label) && onPlay != null
                && result != null && !result.isEmpty()) {
            onPlay.accept(result);
        }
        ImGui.sameLine();
        boolean changed = false;
        if (ImGui.inputText(label, buf)) { result = buf.get(); changed = true; }
        if (ImGui.beginDragDropTarget()) {
            Object p = ImGui.acceptDragDropPayload("CHAR_MODEL");
            if (p instanceof String) { result = (String) p; buf.set(result); changed = true; }
            ImGui.endDragDropTarget();
        }
        if (changed && onAssign != null) onAssign.run();
        return result;
    }

    boolean   isOpen()        { return open.get(); }
    ImBoolean openFlag()      { return open; }
    boolean   isShowCollision() { return showCollision.get(); }
    boolean   isShowBones()     { return showBones.get(); }
}
