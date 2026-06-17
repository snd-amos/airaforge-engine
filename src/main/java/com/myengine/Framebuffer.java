package com.myengine;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
// GL_DEPTH24_STENCIL8 and GL_DEPTH_STENCIL_ATTACHMENT live in core 3.0.

class Framebuffer {
    private int fboID = 0;
    private int textureID = 0;
    private int rboID = 0;
    private int width, height;

    public Framebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        generateFbo();
    }

    private void generateFbo() {
        fboID = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);

        textureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureID);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureID, 0);

        rboID = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, rboID);
        // Combined depth+stencil so the selection outline pass can use the
        // stencil buffer for proper mesh silhouettes (mark mesh pixels, then
        // draw inflated copy only where stencil != mark).
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rboID);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("Framebuffer is not complete!");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);
        glViewport(0, 0, width, height);
    }

    public void unbind(int screenWidth, int screenHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth <= Config.MIN_VIEWPORT_WIDTH || newHeight <= Config.MIN_VIEWPORT_HEIGHT) {
            return;
        }
        if (newWidth == width && newHeight == height) {
            return;
        }

        this.width = newWidth;
        this.height = newHeight;

        // Reallocate attachments in place rather than tearing down the FBO.
        // Several resize() calls fire in quick succession during ImGui
        // dockspace settling on startup; reusing GL handles avoids churn.
        glBindTexture(GL_TEXTURE_2D, textureID);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, 0);

        glBindRenderbuffer(GL_RENDERBUFFER, rboID);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);

        Logger.debug("Framebuffer resized to " + newWidth + "x" + newHeight);
    }

    public int getTextureID() {
        return textureID;
    }

    public void destroy() {
        Logger.debug("Destroying Framebuffer");
        glDeleteTextures(textureID);
        glDeleteRenderbuffers(rboID);
        glDeleteFramebuffers(fboID);
    }
}
