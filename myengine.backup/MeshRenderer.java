package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

class MeshRenderer extends Component {
    public String texturePath = "test.png";
    public transient int textureID = 0;

    public String modelPath = "";
    public transient Mesh mesh = null;

    public Material material = new Material();

    @Override
    public void start() {
        if (!modelPath.isEmpty()) {
            mesh = AssetPool.getMesh(modelPath);
        }
        if (!texturePath.isEmpty()) {
            textureID = AssetPool.getTexture(texturePath);
        }
        // prefer embedded GLB texture, then try foo.glb → foo.png
        if (!modelPath.isEmpty() && "test.png".equals(texturePath)) {
            int embedded = AssetPool.getEmbeddedTexture(modelPath);
            if (embedded != 0) {
                textureID = embedded;
            } else {
                String auto = modelPath.replaceAll("\\.(glb|gltf)$", ".png");
                if (Texture.exists(auto)) {
                    texturePath = auto;
                    textureID = AssetPool.getTexture(auto);
                }
            }
        }
    }

    @Override
    public void update(double dt) {
    }
}