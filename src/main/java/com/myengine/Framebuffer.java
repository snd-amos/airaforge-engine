package com.myengine;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL30.*;

/**
 * Off-screen framebuffer for rendering to texture.
 * Used for the editor scene viewport rendering.
 */
public class Framebuffer {
    private int fboID = 0;
    private int textureID = 0;
    private int rboID = 0;
    private int width, height;

    /**
     * Create a framebuffer with the given dimensions.
     */
    public Framebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        generateFbo();
    }

    /**
     * Generate the framebuffer, color texture, and depth renderbuffer.
     */
    private void generateFbo() {
        // Create the Framebuffer
        fboID = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);

        // Create the Color Texture
        textureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureID);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureID, 0);

        // Create Depth Renderbuffer for 3D geometry depth testing
        rboID = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, rboID);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT32, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboID);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("Framebuffer is not complete!");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Bind this framebuffer for rendering to it.
     */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);
        glViewport(0, 0, width, height);
    }

    /**
     * Unbind this framebuffer and render to the screen instead.
     */
    public void unbind(int screenWidth, int screenHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);
    }

    /**
     * Resize the framebuffer to new dimensions.
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= Config.MIN_VIEWPORT_WIDTH || newHeight <= Config.MIN_VIEWPORT_HEIGHT) {
            Logger.warn("Attempted to resize framebuffer to invalid dimensions: "
                    + newWidth + "x" + newHeight);
            return;
        }

        this.width = newWidth;
        this.height = newHeight;

        // Delete old buffers
        glDeleteTextures(textureID);
        glDeleteRenderbuffers(rboID);
        glDeleteFramebuffers(fboID);

        // Re-generate with new size
        generateFbo();
        Logger.debug("Framebuffer resized to " + newWidth + "x" + newHeight);
    }

    /**
     * Get the color texture ID for rendering in ImGui.
     */
    public int getTextureID() {
        return textureID;
    }

    /**
     * Destroy the framebuffer and free GPU memory.
     */
    public void destroy() {
        Logger.debug("Destroying Framebuffer");
        glDeleteTextures(textureID);
        glDeleteRenderbuffers(rboID);
        glDeleteFramebuffers(fboID);
    }
}