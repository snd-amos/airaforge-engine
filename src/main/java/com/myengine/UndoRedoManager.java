package com.myengine;

import java.util.*;

class UndoRedoManager {
    private Stack<Command> undoStack = new Stack<>();
    private Stack<Command> redoStack = new Stack<>();
    private static final int MAX_HISTORY = 100;

    public void execute(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();

        if (undoStack.size() > MAX_HISTORY) {
            undoStack.remove(0);
        }
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
