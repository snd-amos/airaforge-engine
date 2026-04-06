package com.myengine;

/**
 * Abstract command pattern for undo/redo system.
 * Each action (move, rotate, scale, delete, etc.) is encapsulated as a Command.
 */
public abstract class Command {
    /**
     * Execute the command.
     */
    public abstract void execute();

    /**
     * Undo the command.
     */
    public abstract void undo();

    /**
     * Get a human-readable description for the undo/redo UI.
     */
    public abstract String getDescription();
}