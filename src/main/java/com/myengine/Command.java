package com.myengine;

abstract class Command {
    public abstract void execute();
    public abstract void undo();
    public abstract String getDescription();
}
