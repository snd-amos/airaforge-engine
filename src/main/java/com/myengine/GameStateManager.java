package com.myengine;

class GameStateManager {
    public static Scene currentScene = null;
    private static boolean winRequested = false;
    // Phase 3 (AAA overhaul): runtime simulation state, driven by the Play/Stop
    // toolbar (separate from editor/fullscreen-play mode).
    private static boolean playing = false;
    public static void setPlaying(boolean p) { playing = p; }
    public static boolean isPlaying()        { return playing; }

    public static void changeScene(Scene newScene) {
        if (currentScene != null) {
            Logger.info("Destroying scene: " + currentScene.getClass().getSimpleName());
            currentScene.destroy();
        }

        currentScene = newScene;
        Logger.info("Initializing scene: " + newScene.getClass().getSimpleName());
        currentScene.init();
    }

    /** Called by a WinZone trigger when the player enters its volume. */
    public static void requestWin() { winRequested = true; }

    /** Polled by the scene's main loop. Returns true once per request. */
    public static boolean consumeWinRequest() {
        boolean r = winRequested;
        winRequested = false;
        return r;
    }
}
