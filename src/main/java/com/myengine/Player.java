package com.myengine;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

class Player {
    public enum State { IDLE, WALKING, RUNNING, IN_AIR, WALL_RUNNING, LEDGE_GRABBING, SLIDING, CLIMBING }
    public State currentState = State.IDLE;

    public float climbStamina = Config.CLIMB_MAX_STAMINA;
    private boolean prevE = false;

    public float x = 0.0f, y = Config.PLAYER_START_Y, z = 0.0f;
    public float yaw = 0.0f, pitch = 0.0f, roll = 0.0f;

    public float xVel = 0.0f, yVel = 0.0f, zVel = 0.0f;

    public boolean isGrounded = false;
    public boolean isFlying = false;

    // ---- Capsule collider (the "player body") ----
    // Editable in the editor via the Spawn Point's capsule (Inspector
    // fields / Scale gizmo); applied on play start. Defaults match the
    // old hardcoded constants: radius 0.3, total height 2.0 with the
    // eye at 1.8 (90% of height). ALL body dimensions used by the
    // physics below derive from these two values, so resizing the
    // capsule in the editor genuinely changes how the player collides.
    public float capsuleRadius = Config.PLAYER_RADIUS;
    public float capsuleHeight = 2.0f;
    /** Phase 6B — vertical offset of the collision capsule centre (from the
     *  locomotion component). Shifts feet AND head equally so the whole capsule
     *  rides up/down without changing its length. */
    public float collisionOffsetY = 0.0f;

    /** Phase 4: locomotion attributes driving this controller. Bound by
     *  {@code VulkanMain.setActivePlayer} ("Set as Active Player"). When non-
     *  null the physics below reads speed / jump / capsule / step / eye height
     *  LIVE from it each frame (so the Character Editor sliders tune the active
     *  player); null falls back to the legacy hardcoded defaults. */
    public CharacterLocomotionComponent locomotion = null;

    /** Eye height above the feet — `y` is the eye position. When a locomotion
     *  component is bound its {@code cameraHeightOffset} governs the eye line
     *  (the camera Y); otherwise the legacy 90%-of-capsule value. */
    public float eyeHeight() {
        return (locomotion != null) ? locomotion.cameraHeightOffset : capsuleHeight * 0.9f;
    }
    private float slideEyeHeight()  { return capsuleHeight * 0.4f; }
    // Phase 6B: +collisionOffsetY raises both the feet baseline (smaller feet
    // offset) and the head (larger head room) by the same amount, so the whole
    // capsule shifts up without stretching.
    private float headRoom()        { return capsuleHeight * 0.1f + collisionOffsetY; }
    private float currentFeetOffset() {
        float base = (currentState == State.SLIDING) ? slideEyeHeight() : eyeHeight();
        return base - collisionOffsetY;
    }

    private boolean runningOnLeftWall = false;
    private float grabTargetY = 0.0f;
    private float grabTopY = 0.0f;

    private double jumpCooldown = 0.0;

    private GameObject lastWallJumpedFrom = null;
    private GameObject currentWallObject = null;

    public float currentFov = 70.0f;
    public float cameraYOffset = 0.0f;
    // Visual-only low-pass on eye height for ground-follow snaps (steps,
    // curbs, bumpy walkable meshes). The collider snaps onto the surface
    // instantly; this carries the snap so the CAMERA eases instead, then
    // decays to 0. Decoupled from the body, so physics + horizontal speed
    // stay crisp. Added to the eye position by the camera (VulkanMain).
    public float stepCamOffset = 0.0f;
    private float headBobTime = 0.0f;

    private final float accelGround = 50.0f;
    private final float accelAir = 15.0f;
    // NON-final: Phase 4 refreshes these from `locomotion` each frame when a
    // CharacterLocomotionComponent is bound (else they keep the legacy
    // defaults below as the fallback).
    private float maxWalkSpeed = 6.0f;
    private float maxRunSpeed = 12.0f;
    private final float frictionGround = 10.0f;
    private final float frictionAir = 1.0f;
    private final float gravity = 25.0f;
    private float jumpForce = 10.0f;
    private final float wallJumpForwardForce = 14.0f;
    private final float wallJumpSideForce = 6.0f;
    private final float wallJumpVerticalForce = 8.0f;
    private float stepHeight = Config.PLAYER_STEP_HEIGHT;
    // Base rate (per second) the camera step low-pass (stepCamOffset) eases
    // back to zero. The actual rate also adds horizontal speed so fast
    // stair-running settles quickly instead of trailing the body.
    private final float stepSmoothSpeed = 12.0f;

    public void update(boolean[] keys, World world, boolean isTyping, double dt) {
        if (isTyping) return;

        // Phase 4: live-read movement attributes from the attached locomotion
        // component (set by "Set as Active Player"). Fallback = the legacy
        // hardcoded defaults already in the fields, so an unbound player is
        // unchanged. collisionHalfHeight is half the capsule, so ×2 = height.
        if (locomotion != null) {
            maxWalkSpeed  = locomotion.walkSpeed;
            maxRunSpeed   = locomotion.sprintSpeed;
            jumpForce     = locomotion.jumpVelocity;
            stepHeight    = locomotion.stepHeight;
            capsuleRadius = locomotion.collisionRadius;
            capsuleHeight = locomotion.collisionHalfHeight * 2.0f;
            collisionOffsetY = locomotion.collisionOffsetY;
        }

        float fdt = (float) dt;
        float yawRad = (float) Math.toRadians(yaw);

        if (jumpCooldown > 0) {
            jumpCooldown -= dt;
        }

        if (isGrounded) {
            lastWallJumpedFrom = null;
            currentWallObject = null;
        }

        float fX = (float) Math.sin(yawRad); float fZ = (float) -Math.cos(yawRad);
        float rX = (float) Math.cos(yawRad); float rZ = (float) Math.sin(yawRad);
        float lX = -rX; float lZ = -rZ;

        float dlX = (fX + lX) * 0.707f; float dlZ = (fZ + lZ) * 0.707f;
        float drX = (fX + rX) * 0.707f; float drZ = (fZ + rZ) * 0.707f;

        float sideCheckDist = 0.8f;
        float checkY = y + 1.0f;

        GameObject wallObjectLeft = getWallObject(x + lX * sideCheckDist, checkY, z + lZ * sideCheckDist, world);
        if (wallObjectLeft == null) wallObjectLeft = getWallObject(x + dlX * sideCheckDist, checkY, z + dlZ * sideCheckDist, world);
        boolean isWallLeft = wallObjectLeft != null;

        GameObject wallObjectRight = getWallObject(x + rX * sideCheckDist, checkY, z + rZ * sideCheckDist, world);
        if (wallObjectRight == null) wallObjectRight = getWallObject(x + drX * sideCheckDist, checkY, z + drZ * sideCheckDist, world);
        boolean isWallRight = wallObjectRight != null;

        // --- LEDGE GRAB ALGORITHM ---
        boolean canGrabLedge = false;

        if (currentState == State.IN_AIR && yVel <= 1.0f && keys[GLFW_KEY_E]) {
            GameObject ledgeBlock = getHighestBlockInFront(x + fX * 0.8f, z + fZ * 0.8f, world);

            if (ledgeBlock != null) {
                float topY = ledgeBlock.getMaxY();

                if (y >= topY - 0.5f && y <= topY + 0.5f) {
                    boolean spaceAboveLedge = !checkCollision(x + fX * 0.8f, topY + eyeHeight(), z + fZ * 0.8f, world);

                    if (spaceAboveLedge) {
                        canGrabLedge = true;
                        grabTopY = topY;
                        grabTargetY = topY + 0.2f;
                    }
                }
            }
        }

        if (canGrabLedge) {
            currentState = State.LEDGE_GRABBING;
            lastWallJumpedFrom = null; // Reset wall jump penalty on grab
            currentWallObject = null;
        }

        if (currentState == State.LEDGE_GRABBING) {
            xVel = 0; yVel = 0; zVel = 0;
            y = grabTargetY;
            roll += (0.0f - roll) * 10.0f * fdt;

            if (keys[GLFW_KEY_SPACE] && jumpCooldown <= 0) {
                //TELEPORT
                y = grabTopY + eyeHeight() + 0.01f;
                x += fX * 0.6f;
                z += fZ * 0.6f;

                xVel = 0.0f;
                zVel = 0.0f;
                yVel = -1.0f;

                jumpCooldown = 0.5;
                currentState = State.IN_AIR;
                return;
            } else if (keys[GLFW_KEY_S]) {
                currentState = State.IN_AIR;
                yVel = -2.0f;
                return;
            }

            // SHIMMY MECHANIC
            float shimmySpeed = 3.0f;
            float oldX = x;
            float oldZ = z;

            if (keys[GLFW_KEY_A]) {
                x += lX * shimmySpeed * fdt;
                z += lZ * shimmySpeed * fdt;
            } else if (keys[GLFW_KEY_D]) {
                x += rX * shimmySpeed * fdt;
                z += rZ * shimmySpeed * fdt;
            }

            if (checkCollision(x, y, z, world)) {
                x = oldX;
                z = oldZ;
            }

            // CORNER DETACHMENT
            GameObject currentLedge = getHighestBlockInFront(x + fX * 0.8f, z + fZ * 0.8f, world);
            if (currentLedge == null || Math.abs(currentLedge.getMaxY() - grabTopY) > 0.1f) {
                currentState = State.IN_AIR;
                yVel = -1.0f;
            }

            return;
        }

        // --- WALL CLIMB ALGORITHM ---
        // E edge detection. Update prevE BEFORE any potential early return so it stays in sync.
        boolean ePressed = keys[GLFW_KEY_E] && !prevE;
        prevE = keys[GLFW_KEY_E];

        if (currentState == State.CLIMBING) {
            GameObject wallInFront = getHighestBlockInFront(x + fX * 0.8f, z + fZ * 0.8f, world);

            // Auto-mount when head is close enough to the wall top
            if (wallInFront != null && y >= wallInFront.getMaxY() - Config.CLIMB_MOUNT_THRESHOLD) {
                float topY = wallInFront.getMaxY();
                if (!checkCollision(x + fX * 0.6f, topY + eyeHeight(), z + fZ * 0.6f, world)) {
                    y = topY + eyeHeight() + 0.01f;
                    x += fX * 0.6f;
                    z += fZ * 0.6f;
                    xVel = 0; yVel = 0; zVel = 0;
                    jumpCooldown = 0.3;
                    currentState = State.IN_AIR;
                    return;
                }
            }

            // Exit conditions
            if (!keys[GLFW_KEY_W] || climbStamina <= 0 || wallInFront == null || isFlying) {
                currentState = State.IN_AIR;
                yVel = 0;
            } else {
                float ratio = climbStamina / Config.CLIMB_MAX_STAMINA;
                float climbSpeed = Config.CLIMB_MIN_SPEED + (Config.CLIMB_MAX_SPEED - Config.CLIMB_MIN_SPEED) * ratio;

                y += climbSpeed * fdt;
                climbStamina -= climbSpeed * fdt;
                if (climbStamina < 0) climbStamina = 0;

                xVel = 0; yVel = 0; zVel = 0;
                isGrounded = false;
                roll += (0.0f - roll) * 15.0f * fdt;

                if (keys[GLFW_KEY_SPACE] && jumpCooldown <= 0) {
                    yVel = jumpForce;
                    xVel = -fX * 4.0f;
                    zVel = -fZ * 4.0f;
                    currentState = State.IN_AIR;
                    jumpCooldown = 0.2;
                }
                return;
            }
        } else if (ePressed && keys[GLFW_KEY_W] && climbStamina > 0 && !isFlying
                && currentState != State.SLIDING) {
            // Climb only if the wall in front is too tall to ledge-grab from here.
            GameObject wallToClimb = getHighestBlockInFront(x + fX * 0.8f, z + fZ * 0.8f, world);
            if (wallToClimb != null && wallToClimb.getMaxY() > y + Config.CLIMB_MIN_WALL_HEIGHT) {
                currentState = State.CLIMBING;
                xVel = 0; yVel = 0; zVel = 0;
                lastWallJumpedFrom = null;
                currentWallObject = null;
                return;
            }
        }

        // --- FSM WITH POWER SLIDE, WALL RUN ---
        State previousState = currentState;
        boolean wantWallRunLeft = keys[GLFW_KEY_W] && keys[GLFW_KEY_A] && isWallLeft && !isWallRight;
        boolean wantWallRunRight = keys[GLFW_KEY_W] && keys[GLFW_KEY_D] && isWallRight && !isWallLeft;
        boolean wantSlide = keys[GLFW_KEY_C];

        boolean ceilingAbove = isBlockedAbove(world);
        float currentSpeed = (float) Math.sqrt(xVel*xVel + zVel*zVel);

        if (isGrounded && (wantSlide || (currentState == State.SLIDING && ceilingAbove))) {
            if (currentState != State.SLIDING && currentSpeed > 5.0f && wantSlide) {
                xVel += fX * 6.0f; // Slide Boost!
                zVel += fZ * 6.0f;
            }
            if (currentSpeed > 1.0f || ceilingAbove) {
                currentState = State.SLIDING;
            } else {
                currentState = State.IDLE;
            }
        } else if (!isGrounded && !isFlying && yVel <= 2.0f) {
            if (wantWallRunLeft && wallObjectLeft != lastWallJumpedFrom) {
                currentState = State.WALL_RUNNING;
                runningOnLeftWall = true;
                currentWallObject = wallObjectLeft;
                if (previousState != State.WALL_RUNNING)
                    Logger.info("[WALL-RUN] start on '" + currentWallObject.name + "' (left wall)");
            } else if (wantWallRunRight && wallObjectRight != lastWallJumpedFrom) {
                currentState = State.WALL_RUNNING;
                runningOnLeftWall = false;
                currentWallObject = wallObjectRight;
                if (previousState != State.WALL_RUNNING)
                    Logger.info("[WALL-RUN] start on '" + currentWallObject.name + "' (right wall)");
            } else {
                currentState = State.IN_AIR;
            }
        } else if (!isGrounded && !isFlying) {
            currentState = State.IN_AIR;
        } else if (keys[GLFW_KEY_W] || keys[GLFW_KEY_A] || keys[GLFW_KEY_S] || keys[GLFW_KEY_D]) {
            if (keys[GLFW_KEY_LEFT_SHIFT]) {
                currentState = State.RUNNING;
            } else {
                currentState = State.WALKING;
            }
        } else {
            currentState = State.IDLE;
        }

        // PHYSICAL SLIDING CAMERA DROPS & STAND UPS
        if (previousState != State.SLIDING && currentState == State.SLIDING) {
            y -= 1.0f;
            cameraYOffset = 1.0f; // Counter-weight visual offset to create a smooth animation
        } else if (previousState == State.SLIDING && currentState != State.SLIDING) {
            y += 1.0f;
            cameraYOffset = -1.0f;
        }

        float inputX = 0, inputZ = 0;
        if (currentState != State.SLIDING) {
            if (keys[GLFW_KEY_W]) inputZ -= 1;
            if (keys[GLFW_KEY_S]) inputZ += 1;
            if (keys[GLFW_KEY_A]) inputX -= 1;
            if (keys[GLFW_KEY_D]) inputX += 1;
        }

        float length = (float) Math.sqrt(inputX*inputX + inputZ*inputZ);
        if (length > 0) {
            inputX /= length;
            inputZ /= length;
        }

        float dirX = 0, dirZ = 0;
        if (inputZ < 0) { dirX += fX; dirZ += fZ; }
        if (inputZ > 0) { dirX -= fX; dirZ -= fZ; }
        if (inputX < 0) { dirX += lX; dirZ += lZ; }
        if (inputX > 0) { dirX += rX; dirZ += rZ; }

        float dirLength = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLength > 0) {
            dirX /= dirLength;
            dirZ /= dirLength;
        }

        if (!isFlying) {
            float currentAccel = (currentState == State.IN_AIR || currentState == State.WALL_RUNNING) ? accelAir : accelGround;
            xVel += dirX * currentAccel * fdt;
            zVel += dirZ * currentAccel * fdt;

            float targetMaxSpeed = (currentState == State.RUNNING) ? maxRunSpeed : maxWalkSpeed;
            float currentFriction = (currentState == State.IN_AIR || currentState == State.WALL_RUNNING) ? frictionAir : frictionGround;
            if (currentState == State.SLIDING) currentFriction = 1.8f; // Ice-like sliding!

            if (length == 0 || currentSpeed > targetMaxSpeed || currentState == State.SLIDING) {
                float drop = currentSpeed * currentFriction * fdt;
                float multiplier = Math.max(currentSpeed - drop, 0) / (currentSpeed == 0 ? 1 : currentSpeed);
                xVel *= multiplier;
                zVel *= multiplier;
            }

            // --- Horizontal movement: collide-and-slide ---
            // Cast rays forward into steep geometry and remove the velocity
            // component pointing INTO any wall / ramp, keeping the
            // tangential part. One technique, every case, no glitches:
            //   - walk into a wall   → into-component removed → stop / strafe
            //   - jump + W into a ramp → into-component removed → can't enter
            //   - on a steep ramp     → uphill removed, gravity's downhill
            //                           kept → slide down, never frozen
            // This replaces the old binary "revert the move" wall test
            // (which froze the player on ramps) and never penetrates, so
            // there is nothing to depenetrate (which used to teleport you
            // through thick tilted bars). Up to 3 iterations resolve
            // corners (slide off one wall straight into another).
            float mvx = xVel * fdt, mvz = zVel * fdt;
            for (int iter = 0; iter < 3; iter++) {
                float mlen = (float) Math.sqrt(mvx * mvx + mvz * mvz);
                if (mlen < 1e-6f) break;
                float[] n = castSteepWallNormal(mvx / mlen, mvz / mlen, mlen, world);
                if (n == null) break;                 // path is clear
                float vDotN = mvx * n[0] + mvz * n[1];
                if (vDotN >= 0) break;                // already moving away
                mvx -= vDotN * n[0];                  // strip into-wall component
                mvz -= vDotN * n[1];
            }
            x += mvx;
            z += mvz;
            // Keep velocity consistent with the slid move so we don't keep
            // accumulating speed into a wall we're pressed against.
            xVel = mvx / fdt;
            zVel = mvz / fdt;

            if (currentState == State.WALL_RUNNING) {
                if (yVel < -1.5f) yVel = -1.5f;
                float targetRoll = runningOnLeftWall ? 15.0f : -15.0f;
                roll += (targetRoll - roll) * 10.0f * fdt;
            } else {
                roll += (0.0f - roll) * 15.0f * fdt;
            }

            // --- Vertical: pure raycast ground following ---
            float feetOffset = currentFeetOffset();
            if (yVel > 0) {
                // Rising from a jump. Don't let the arc penetrate a WALKABLE
                // surface we're moving up into — e.g. sprint-jumping up a 45°
                // ramp, where the ramp climbs as fast as the jump so the feet
                // stay under its surface and clip straight through. Ride the
                // surface up instead. A real jump that clears the ramp keeps
                // the feet above walkY, so this no-ops. Steep surfaces have no
                // walkable hit here, so the steep path is unaffected.
                float rfeetY = y - feetOffset;
                float[] rs = probeSurfacesBelow(x, z, world,
                        rfeetY + stepHeight + 0.1f, stepHeight * 4.0f);
                float rWalkY = rs[0];
                if (rWalkY > -Float.MAX_VALUE && rfeetY < rWalkY) {
                    y = rWalkY + feetOffset + 0.005f;
                }
                y += yVel * fdt;
                yVel -= gravity * fdt;
                isGrounded = false;
            } else {
                yVel -= gravity * fdt;
                boolean wasGrounded = isGrounded;
                float feetY = y - feetOffset;
                // Probe from stepHeight above the feet downward. The range
                // extends with fall speed so a fast (or hitched) frame can
                // never out-run the ray — that's what guarantees no
                // tunnelling regardless of framerate.
                float fallStep = -yVel * fdt;
                float probeDist = Math.max(stepHeight * 4.0f, stepHeight + fallStep + 0.5f);
                float[] surf = probeSurfacesBelow(x, z, world,
                        feetY + stepHeight + 0.1f, probeDist);
                float walkY  = surf[0];                 // highest walkable, or -MAX
                float steepY = surf[1];                 // highest steep, or -MAX
                boolean haveWalk  = walkY  > -Float.MAX_VALUE;
                boolean haveSteep = steepY > -Float.MAX_VALUE;
                float futureFeet = feetY + yVel * fdt;

                // --- Collision diagnostics: hold G to dump the probe state ---
                // STEEP engages when the surface is under the feet AND either
                // the feet are sinking through it (fast-fall, no tunnelling) OR
                // the feet are already resting just above it (hysteresis — the
                // clamp parks them 5mm above, so without this the branch drops
                // to FALL every other frame → 4mm jitter).
                boolean onSteep = haveSteep && steepY <= feetY + 0.1f
                        && (futureFeet <= steepY || feetY <= steepY + 0.06f);

                if (haveWalk && (futureFeet <= walkY
                        || (wasGrounded && Math.abs(feetY - walkY) <= stepHeight))) {
                    // Stand on / follow WALKABLE ground. Rest a hair above
                    // the face so the ray doesn't graze-miss it next frame.
                    //
                    // Snap the BODY straight onto the surface every frame — no
                    // Y-lerp on the collider. This is what kills the two KCC
                    // bugs the old body-lerp caused:
                    //   * Ramp jitter — a continuously-rising walkable slope no
                    //     longer lags-then-catches; the feet sit exactly on the
                    //     ramp, so the slope-follow is smooth instead of micro-
                    //     stepping. (No normal filter needed: with the body
                    //     snapped, a ramp is just ground-follow, never a step.)
                    //   * Stair stall — the feet never lag below a tread, so the
                    //     forward wall-cast keeps full knee clearance over the
                    //     next riser and horizontal velocity is never blocked
                    //     between consecutive steps.
                    // The smoothness moves to the CAMERA (stepCamOffset, eased
                    // to 0 in the camera-dynamics block): a visual low-pass on
                    // the eye so steps, curbs and bumpy walkable meshes (the
                    // police siren) still look smooth while physics stays crisp
                    // and X/Z momentum is fully preserved.
                    float targetY = walkY + feetOffset + 0.005f;
                    float groundSnap = targetY - y;
                    // Feed the snap into the camera low-pass ONLY for genuine
                    // ground-follow steps: already grounded, within step height,
                    // and not shoving the eye up into a ceiling. Hard landings
                    // from a fall (!wasGrounded) and big corrections skip the
                    // ease so impacts stay crisp (no float-down).
                    if (wasGrounded && Math.abs(groundSnap) <= stepHeight + 0.01f
                            && !(groundSnap > 0 && ceilingAbove)) {
                        stepCamOffset -= groundSnap;
                        if (stepCamOffset >  stepHeight) stepCamOffset =  stepHeight;
                        if (stepCamOffset < -stepHeight) stepCamOffset = -stepHeight;
                    }
                    y = targetY;
                    isGrounded = true;
                    yVel = 0;
                } else if (onSteep) {
                    // We're ON TOP of a STEEP surface (it's at/below the
                    // feet) and gravity would sink the feet through it this
                    // frame. Clamp the feet onto the surface — ray-based, so
                    // it can NEVER tunnel or teleport across the solid the
                    // way sphere depenetration did — and strip the velocity
                    // component pointing into the slope, leaving the
                    // downhill part. Not grounded: gravity keeps feeding the
                    // downhill slide every frame, so a too-steep ramp slides
                    // you off instead of letting you stand or fall through.
                    // The `steepY <= feetY` guard means a steep face RISING
                    // beside you (caught by an edge ray, but above your feet)
                    // does NOT yank you onto it — that was the phantom-slide.
                    y = steepY + feetOffset + 0.005f;
                    float nx = surf[2], ny = surf[3], nz = surf[4];
                    float vDotN = xVel * nx + yVel * ny + zVel * nz;
                    if (vDotN < 0) {
                        xVel -= vDotN * nx;
                        yVel -= vDotN * ny;
                        zVel -= vDotN * nz;
                    }
                    isGrounded = false;
                } else {
                    // Nothing reachable under the feet — free fall. The
                    // probe range covers this frame's fall step, so the
                    // surface is caught before the feet can pass it.
                    y += yVel * fdt;
                    isGrounded = false;
                }
            }

            // Oriented-box depenetration for TILTED cubes (ramps). The ray
            // ground-probe can't keep the capsule out of a tilted box it falls
            // into at an angle (the center-ray punches through the near face),
            // so push the body out of the box's nearest face. Restricted to
            // rotated cubes — axis-aligned cubes are handled by the step/wall
            // logic above and must not be disturbed.
            resolveCubePenetration(world);

            if (keys[GLFW_KEY_SPACE] && jumpCooldown <= 0) {
                if (isGrounded && !ceilingAbove) {
                    yVel = jumpForce;
                    isGrounded = false;
                    currentState = State.IN_AIR;
                    jumpCooldown = 0.2;
                } else if (currentState == State.WALL_RUNNING) {
                    yVel = wallJumpVerticalForce;

                    float pushOffX = runningOnLeftWall ? rX : lX;
                    float pushOffZ = runningOnLeftWall ? rZ : lZ;

                    xVel = (fX * wallJumpForwardForce) + (pushOffX * wallJumpSideForce);
                    zVel = (fZ * wallJumpForwardForce) + (pushOffZ * wallJumpSideForce);

                    currentState = State.IN_AIR;
                    jumpCooldown = 0.2;
                    lastWallJumpedFrom = currentWallObject;
                    currentWallObject = null;
                }
            }
        } else {
            float flySpeed = 20.0f;
            x += dirX * flySpeed * fdt;
            z += dirZ * flySpeed * fdt;
            if (keys[GLFW_KEY_SPACE]) y += flySpeed * fdt;
            // Ctrl held → C is the Copy shortcut, not fly-down.
            if (keys[GLFW_KEY_C] && !keys[GLFW_KEY_LEFT_CONTROL]) y -= flySpeed * fdt;
            yVel = 0;
            roll = 0.0f;
        }

        // --- PROCEDURAL CAMERA DYNAMICS CALCULATIONS ---
        float targetFov = 70.0f;
        if (currentState == State.RUNNING || currentState == State.WALL_RUNNING) targetFov = 90.0f;
        if (currentState == State.SLIDING) targetFov = 100.0f;
        currentFov += (targetFov - currentFov) * 5.0f * fdt;

        float targetCamOffset = 0.0f;
        if (isGrounded && currentSpeed > 1.0f && currentState != State.SLIDING) {
            headBobTime += currentSpeed * fdt * 1.5f;
            float bobAmp = (currentState == State.RUNNING) ? 0.12f : 0.06f;
            targetCamOffset = (float) Math.sin(headBobTime) * bobAmp;
        } else {
            headBobTime = 0;
        }
        cameraYOffset += (targetCamOffset - cameraYOffset) * 15.0f * fdt;

        // Decay the step low-pass back to zero. Rate = base + horizontal speed,
        // so standing on a curb eases gently while running up stairs settles
        // fast (the eye never trails far behind the already-snapped body).
        float stepEase = (stepSmoothSpeed + currentSpeed) * fdt;
        if (stepEase > 1.0f) stepEase = 1.0f;
        stepCamOffset -= stepCamOffset * stepEase;

        // Regenerate climb stamina while grounded
        if (isGrounded && currentState != State.CLIMBING && climbStamina < Config.CLIMB_MAX_STAMINA) {
            climbStamina += Config.CLIMB_STAMINA_REGEN * fdt;
            if (climbStamina > Config.CLIMB_MAX_STAMINA) climbStamina = Config.CLIMB_MAX_STAMINA;
        }
    }

    // Collide-and-slide helper: cast rays forward (move direction) into
    // STEEP geometry and return the horizontal normal of the nearest wall /
    // ramp within reach, or null if the path is clear. Used to strip the
    // into-wall velocity component so the player slides instead of stopping
    // dead, freezing, or penetrating.
    //
    // Sampling covers the capsule volume that matters: 3 heights from the
    // KNEE (just above step height, so low curbs / ramp feet aren't treated
    // as walls — you step/walk onto those) up to the head, and 3 lateral
    // offsets across the capsule width so a wall the capsule's side would
    // graze is still caught. Walkable surfaces (slope <= 45°) are skipped —
    // you walk onto those, they're handled by the vertical ground probe.
    private float[] castSteepWallNormal(float dx, float dz, float moveLen, World world) {
        float reach = moveLen + capsuleRadius + 0.05f;
        float feetY = y - currentFeetOffset();
        float kneeY = feetY + stepHeight + 0.05f;
        float headY = y + headRoom();
        if (headY < kneeY) headY = kneeY;
        float[] heights = { kneeY, (kneeY + headY) * 0.5f, headY };
        // Perpendicular to the move direction, in the horizontal plane.
        float perpX = -dz, perpZ = dx;
        float[] offsets = { 0f, capsuleRadius, -capsuleRadius };

        float bestDist = Float.MAX_VALUE, bnx = 0, bnz = 0;
        List<GameObject> nearby = world.getNearbyEntities(x, z);
        for (float hy : heights) {
            for (float off : offsets) {
                float ox = x + perpX * off, oz = z + perpZ * off;
                for (GameObject b : nearby) {
                    float[] r = MeshCollider.rayMeshNormal(b, ox, hy, oz, dx, 0, dz);
                    if (r == null || r[0] <= 0 || r[0] > reach) continue;
                    if (Math.abs(r[2]) >= Config.WALKABLE_NORMAL_Y) continue;  // walkable, not a wall
                    if (r[0] < bestDist) {
                        // Horizontal part of the (origin-facing) face normal.
                        float hnx = r[1], hnz = r[3];
                        float hl = (float) Math.sqrt(hnx * hnx + hnz * hnz);
                        if (hl < 1e-6f) continue;   // purely vertical normal — no horizontal block
                        bestDist = r[0];
                        bnx = hnx / hl; bnz = hnz / hl;
                    }
                }
            }
        }
        return (bestDist < Float.MAX_VALUE) ? new float[]{ bnx, bnz } : null;
    }

    // Probes straight down at 5 points across the footprint (center + 4
    // edges) and reports the highest WALKABLE surface and the highest
    // STEEP surface SEPARATELY:
    //   [0] walkableY  — highest surface with slope <= 45°, or -MAX
    //   [1] steepY     — highest surface with slope > 45°, or -MAX
    //   [2..4] steepNx/Ny/Nz — that steep surface's normal (for the slide)
    //
    // Keeping the two categories apart is the whole trick. The caller
    // prefers walkable ground for standing; the steep result is used ONLY
    // to keep the player from sinking through a too-steep face they're on
    // top of. A steep face that an edge ray catches ABOVE the feet (a ramp
    // rising beside the player) is therefore ignored for standing AND for
    // the slide — no phantom slide, no snapping onto walls.
    //
    // Ray-based by design: a downward ray always finds a surface from
    // above and lets the caller place the feet on top of it, so unlike
    // sphere depenetration it can never push the player to the wrong side
    // of a thick tilted slab (the teleport-through bug).
    private float[] probeSurfacesBelow(float px, float pz, World world,
                                       float startY, float maxDist) {
        float r = capsuleRadius * 0.9f;
        float[] sxz = { px, pz,  px - r, pz,  px + r, pz,  px, pz - r,  px, pz + r };

        float walkY = -Float.MAX_VALUE;
        float steepY = -Float.MAX_VALUE, snx = 0, sny = 1, snz = 0;
        for (int s = 0; s < sxz.length; s += 2) {
            float sampX = sxz[s], sampZ = sxz[s + 1];
            List<GameObject> nearby = world.getNearbyEntities(sampX, sampZ);
            for (GameObject b : nearby) {
                float[] hit = MeshCollider.rayMeshNormal(b, sampX, startY, sampZ, 0, -1, 0);
                if (hit == null || hit[0] <= 0 || hit[0] > maxDist) continue;
                float gy = startY - hit[0];
                float ny = Math.abs(hit[2]);
                if (ny >= Config.WALKABLE_NORMAL_Y) {
                    if (gy > walkY) walkY = gy;
                } else if (gy > steepY) {
                    steepY = gy; snx = hit[1]; sny = ny; snz = hit[3];
                }
            }
        }
        return new float[]{ walkY, steepY, snx, sny, snz };
    }

    // Push the capsule out of any TILTED cube it has sunk into. Samples three
    // spheres along the spine (feet / mid / head) against each rotated cube and
    // applies the largest min-translation push. Axis-aligned cubes are skipped
    // (the step/wall collision owns those). Bounded + exits the nearest face,
    // so it can't teleport the player across the solid.
    private void resolveCubePenetration(World world) {
        float feetY = y - currentFeetOffset();
        float headY = y + headRoom();
        float[] sampleY = { feetY + capsuleRadius, (feetY + headY) * 0.5f, headY - capsuleRadius };

        float bestMag = 0, bx = 0, by = 0, bz = 0;
        for (GameObject b : world.getNearbyEntities(x, z)) {
            MeshRenderer mr = b.getComponent(MeshRenderer.class);
            if (mr == null) continue;
            boolean isCube = mr.modelPath == null || mr.modelPath.isEmpty()
                    || World.CUBE_MESH_KEY.equals(mr.modelPath);
            if (!isCube) continue;
            // Only rotated cubes — axis-aligned ones are handled elsewhere and
            // pushing the player out of them would break step-up onto curbs.
            if (isAxisAligned(b.transform)) continue;

            for (float sampY : sampleY) {
                float[] push = MeshCollider.pushOutOfCube(b, x, sampY, z, capsuleRadius);
                if (push == null) continue;
                float mag = push[0] * push[0] + push[1] * push[1] + push[2] * push[2];
                if (mag > bestMag) { bestMag = mag; bx = push[0]; by = push[1]; bz = push[2]; }
            }
        }
        if (bestMag <= 0) return;

        x += bx; y += by; z += bz;
        // Kill the velocity component pointing into the face we pushed off of,
        // so we don't immediately sink back in (the along-face slide is kept).
        float pl = (float) Math.sqrt(bestMag);
        float nx = bx / pl, ny = by / pl, nz = bz / pl;
        float vDotN = xVel * nx + yVel * ny + zVel * nz;
        if (vDotN < 0) { xVel -= vDotN * nx; yVel -= vDotN * ny; zVel -= vDotN * nz; }
    }

    private static boolean isAxisAligned(Transform t) {
        return isMultipleOf90(t.rotX) && isMultipleOf90(t.rotY) && isMultipleOf90(t.rotZ);
    }

    private static boolean isMultipleOf90(float deg) {
        float m = ((deg % 90f) + 90f) % 90f;
        return m < 1f || m > 89f;
    }

    private GameObject getHighestBlockInFront(float px, float pz, World world) {
        GameObject highest = null;
        float maxY = -1000f;
        List<GameObject> nearby = world.getNearbyEntities(px, pz);
        for (GameObject b : nearby) {
            float r = capsuleRadius + 0.1f;
            if (px + r > b.getMinX() && px - r < b.getMaxX() &&
                    pz + r > b.getMinZ() && pz - r < b.getMaxZ()) {
                if (b.getMaxY() > maxY) {
                    maxY = b.getMaxY();
                    highest = b;
                }
            }
        }
        return highest;
    }

    // BUG FIX
    private boolean isBlockedAbove(World world) {
        List<GameObject> nearby = world.getNearbyEntities(x, z);
        for (GameObject b : nearby) {
            if (MeshCollider.intersectsAABB(b,
                    x - capsuleRadius, y + headRoom(), z - capsuleRadius,
                    x + capsuleRadius, y + headRoom() + 1.0f, z + capsuleRadius)) {
                return true;
            }
        }
        return false;
    }

    private GameObject getWallObject(float px, float py, float pz, World world) {
        float dynamicFeet = currentFeetOffset();
        List<GameObject> nearby = world.getNearbyEntities(px, pz);
        for (GameObject b : nearby) {
            // Wall-running is no longer global: the surface must belong to a
            // GameObject explicitly tagged WallRunnable. (Strict gameplay
            // gating — designers opt walls in per the component spec.)
            if (b.getComponent(WallRunnableComponent.class) == null) continue;
            // Still require a steep face so a walkable ramp never triggers it.
            if (MeshCollider.intersectsAABBSteep(b,
                    px - capsuleRadius, py - dynamicFeet, pz - capsuleRadius,
                    px + capsuleRadius, py + headRoom(), pz + capsuleRadius,
                    Config.WALKABLE_NORMAL_Y)) {
                return b;
            }
        }
        return null;
    }

    // Full player AABB collision. Used by the ledge grab / climb / wall
    // checks that want to know if anything intersects the body. Horizontal
    // movement no longer uses this — capsule collide-and-slide handles that.
    private boolean checkCollision(float px, float py, float pz, World world) {
        float dynamicFeet = currentFeetOffset();
        float dynamicHead = headRoom();

        List<GameObject> nearby = world.getNearbyEntities(px, pz);
        for (GameObject b : nearby) {
            if (MeshCollider.intersectsAABB(b,
                    px - capsuleRadius, py - dynamicFeet, pz - capsuleRadius,
                    px + capsuleRadius, py + dynamicHead, pz + capsuleRadius)) {
                return true;
            }
        }
        return false;
    }

    public float[] getMouseRay(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        return RayCaster.getMouseRay((float)mouseX, (float)mouseY, screenWidth, screenHeight,
                Config.FOV, pitch, yaw);
    }

    public GameObject getEntityAtMouse(World world, double mouseX, double mouseY, int screenWidth, int screenHeight) {
        float[] ray = getMouseRay(mouseX, mouseY, screenWidth, screenHeight);
        GameObject closest = null;
        float minDist = Float.MAX_VALUE;

        for (GameObject b : world.entities) {
            float t = MeshCollider.rayMesh(b, this.x, this.y, this.z, ray[0], ray[1], ray[2]);
            if (t > 0 && t < minDist) {
                minDist = t;
                closest = b;
            }
        }
        return closest;
    }

    public GameObject getLookTarget(World world, float range) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float dirX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dirY = (float) (-Math.sin(pitchRad));
        float dirZ = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));

        GameObject closest = null;
        float minDist = Float.MAX_VALUE;
        List<GameObject> nearby = world.getNearbyEntities(this.x, this.z);

        for (GameObject b : nearby) {
            float t = MeshCollider.rayMesh(b, this.x, this.y, this.z, dirX, dirY, dirZ);
            if (t > 0 && t <= range && t < minDist) {
                minDist = t;
                closest = b;
            }
        }
        return closest;
    }
}
