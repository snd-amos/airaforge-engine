package com.myengine;

/**
 * Command for undoing/redoing object deletion.
 * Stores the deleted object and its position in the entity list.
 */
public class DeleteObjectCommand extends Command {
    private World world;
    private GameObject gameObject;
    private int deletionIndex;

    /**
     * Create a delete command.
     */
    public DeleteObjectCommand(World world, GameObject gameObject) {
        this.world = world;
        this.gameObject = gameObject;
        this.deletionIndex = world.entities.indexOf(gameObject);
    }

    @Override
    public void execute() {
        // Remove the object
        if (world != null && gameObject != null && world.entities.contains(gameObject)) {
            world.entities.remove(gameObject);
        }
    }

    @Override
    public void undo() {
        // Restore the object at its original position
        if (world != null && gameObject != null && !world.entities.contains(gameObject)) {
            if (deletionIndex >= 0 && deletionIndex <= world.entities.size()) {
                world.entities.add(deletionIndex, gameObject);
            } else {
                world.entities.add(gameObject);
            }
        }
    }

    @Override
    public String getDescription() {
        return "Delete " + (gameObject != null ? gameObject.name : "Unknown");
    }
}