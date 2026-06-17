package com.myengine;

abstract class Component {
    public transient GameObject gameObject = null;
    public String type = this.getClass().getSimpleName();

    public void start() {}
    public void update(double dt) {}
}
