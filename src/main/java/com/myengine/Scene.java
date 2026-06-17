package com.myengine;

abstract class Scene {
    public abstract void init();
    public abstract void update(double dt, boolean[] keys);
    public abstract void render();
    public abstract void destroy();
    public abstract void onMouseClick(int button, int action, double mouseX, double mouseY);
    public abstract void onMouseMove(double xpos, double ypos);
    public abstract void onKeyPress(int key, int action);
}
