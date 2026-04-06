package com.myengine;

/**
 * Manages scene transitions and state.
 * Handles cleanup when switching scenes.
 */
public class GameStateManager {
    public static Scene currentScene = null;

    /**
     * Change to a new scene, cleaning up the old one.
     */
    public static void changeScene(Scene newScene) {
        if (currentScene != null) {
            Logger.info("Destroying scene: " + currentScene.getClass().getSimpleName());
            currentScene.destroy();
        }

        currentScene = newScene;
        Logger.info("Initializing scene: " + newScene.getClass().getSimpleName());
        currentScene.init();
    }
}