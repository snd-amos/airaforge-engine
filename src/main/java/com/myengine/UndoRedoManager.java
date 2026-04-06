package com.myengine;

import java.util.Stack;

/**
 * Manages undo/redo functionality for the editor.
 * Tracks all state-changing operations as Commands.
 */
public class UndoRedoManager {
    private Stack<Command> undoStack = new Stack<>();
    private Stack<Command> redoStack = new Stack<>();
    private static final int MAX_HISTORY = 100;

    /**
     * Execute a command and push it to undo stack.
     */
    public void execute(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear(); // Redo stack is cleared when new command executed

        // Limit history to prevent memory issues
        if (undoStack.size() > MAX_HISTORY) {
            undoStack.remove(0);
        }
    }

    /**
     * Undo the last command.
     */
    public void undo() {
        if (undoStack.isEmpty()) return;
        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);
    }

    /**
     * Redo the last undone command.
     */
    public void redo() {
        if (redoStack.isEmpty()) return;
        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);
    }

    /**
     * Check if undo is available.
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Check if redo is available.
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Clear all undo/redo history.
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}