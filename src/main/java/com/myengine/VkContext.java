package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static com.myengine.VkUtil.check;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Owns the long-lived Vulkan handles that are created once at startup and
 * never recreated: instance, debug messenger, window surface, physical
 * device, logical device, queue families + queues, and the graphics
 * command pool.
 *
 * VulkanMain holds one of these and pulls handles out of it; everything
 * that needs "the device" or "the graphics queue" just asks the context.
 * Stable for the life of the application.
 */
final class VkContext {

    private final boolean validationEnabled;

    private VkInstance       instance;
    private long             debugMessenger;     // VkDebugUtilsMessengerEXT
    private long             surface;            // VkSurfaceKHR
    private VkPhysicalDevice physicalDevice;
    private VkDevice         device;
    private int              graphicsFamily = -1;
    private int              presentFamily  = -1;
    private VkQueue          graphicsQueue;
    private VkQueue          presentQueue;
    private long             commandPool;

    /** Build the entire init chain. Throws on any failure — the partially-
     *  constructed instance is unsafe to use, so caller should bail. */
    VkContext(long glfwWindow, boolean enableValidation) {
        this.validationEnabled = enableValidation;
        createInstance();
        if (validationEnabled) setupDebugMessenger();
        createSurface(glfwWindow);
        pickPhysicalDevice();
        createLogicalDevice();
        createCommandPool();
        Logger.info("[VK] Context ready");
    }

    // ---- accessors ----
    VkInstance       instance()       { return instance; }
    long             surface()        { return surface; }
    VkPhysicalDevice physicalDevice() { return physicalDevice; }
    VkDevice         device()         { return device; }
    int              graphicsFamily() { return graphicsFamily; }
    int              presentFamily()  { return presentFamily; }
    VkQueue          graphicsQueue()  { return graphicsQueue; }
    VkQueue          presentQueue()   { return presentQueue; }
    long             commandPool()    { return commandPool; }

    // ============================================================
    //                          Setup
    // ============================================================

    private void createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("JEngine"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("JEngine"))
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK_API_VERSION_1_2);

            // Required instance extensions:
            //   - GLFW's required set (KHR_surface + platform surface ext)
            //   - VK_EXT_debug_utils when validation is enabled
            //   - VK_KHR_portability_enumeration on macOS so MoltenVK shows up
            PointerBuffer glfwExts = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (glfwExts == null) throw new RuntimeException(
                    "glfwGetRequiredInstanceExtensions returned null");

            boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            int extraExts = (validationEnabled ? 1 : 0) + (isMac ? 1 : 0);
            PointerBuffer enabledExts = stack.mallocPointer(glfwExts.remaining() + extraExts);
            enabledExts.put(glfwExts);
            if (validationEnabled) enabledExts.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            if (isMac) enabledExts.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
            enabledExts.flip();

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(enabledExts);

            if (isMac) createInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);

            if (validationEnabled) {
                if (!checkValidationLayerSupport()) {
                    Logger.warn("[VK] Validation layer requested but VK_LAYER_KHRONOS_validation " +
                            "is not present. Install the Vulkan SDK or skip validation. Continuing without.");
                } else {
                    PointerBuffer layers = stack.mallocPointer(1);
                    layers.put(stack.UTF8("VK_LAYER_KHRONOS_validation")).flip();
                    createInfo.ppEnabledLayerNames(layers);
                    // pNext'd messenger catches errors during vkCreateInstance itself.
                    VkDebugUtilsMessengerCreateInfoEXT dbg = makeDebugMessengerCreateInfo(stack);
                    createInfo.pNext(dbg.address());
                }
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            instance = new VkInstance(pInstance.get(0), createInfo);
        }
        Logger.info("[VK] Instance created");
    }

    private boolean checkValidationLayerSupport() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(count, null);
            if (count.get(0) == 0) return false;
            VkLayerProperties.Buffer props = VkLayerProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceLayerProperties(count, props);
            for (int i = 0; i < props.remaining(); i++) {
                if ("VK_LAYER_KHRONOS_validation".equals(props.get(i).layerNameString())) return true;
            }
            return false;
        }
    }

    private VkDebugUtilsMessengerCreateInfoEXT makeDebugMessengerCreateInfo(MemoryStack stack) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback((severity, type, pCallbackData, userData) -> {
                    VkDebugUtilsMessengerCallbackDataEXT data =
                            VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    String msg = memUTF8(data.pMessage());
                    // Third-party overlay hooks (OBS / Wallpaper Engine
                    // screen capture) inject their Vulkan layer into every
                    // process; the loader warns when the 32-bit and 64-bit
                    // manifests collide ("Removing layer VK_LAYER_WEOBS_HOOK
                    // ... because it is a duplicate"). Nothing we control,
                    // nothing wrong with our engine — silence it.
                    if (msg.contains("Removing layer") && msg.contains("duplicate")) {
                        return VK_FALSE;
                    }
                    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        Logger.error("[VK-VALIDATION] " + msg);
                    } else {
                        Logger.warn("[VK-VALIDATION] " + msg);
                    }
                    return VK_FALSE;
                });
    }

    private void setupDebugMessenger() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT info = makeDebugMessengerCreateInfo(stack);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDebugUtilsMessengerEXT(instance, info, null, p),
                    "vkCreateDebugUtilsMessengerEXT");
            debugMessenger = p.get(0);
        }
        Logger.info("[VK] Debug messenger installed");
    }

    private void createSurface(long glfwWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(GLFWVulkan.glfwCreateWindowSurface(instance, glfwWindow, null, p),
                    "glfwCreateWindowSurface");
            surface = p.get(0);
        }
        Logger.info("[VK] Window surface created");
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, count, null);
            if (count.get(0) == 0) throw new RuntimeException("No Vulkan-capable GPU found");
            PointerBuffer pDevices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, pDevices);

            // Score every device and take the best (prefers discrete GPU).
            VkPhysicalDevice best = null;
            int bestScore = -1;
            for (int i = 0; i < pDevices.remaining(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(i), instance);
                int score = scoreDevice(candidate, stack);
                if (score > bestScore) { bestScore = score; best = candidate; }
            }
            if (best == null || bestScore < 0) {
                throw new RuntimeException("No suitable GPU (need graphics+present queues + swapchain support)");
            }
            physicalDevice = best;

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            Logger.info("[VK] Picked GPU: " + props.deviceNameString()
                    + " (type=" + deviceTypeName(props.deviceType()) + ")");

            // Locate queue families on the chosen device (side-effects the fields).
            if (!findQueueFamiliesFor(physicalDevice, stack)) {
                throw new RuntimeException("Picked device has no graphics+present queues — shouldn't happen");
            }
            Logger.info("[VK] Queue families: graphics=" + graphicsFamily + " present=" + presentFamily);
        }
    }

    private int scoreDevice(VkPhysicalDevice dev, MemoryStack stack) {
        if (!deviceSupportsSwapchain(dev, stack)) return -1;
        if (!findQueueFamiliesFor(dev, stack)) return -1;
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(dev, props);
        if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) return 1000;
        if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) return 100;
        return 0;
    }

    private boolean deviceSupportsSwapchain(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (CharSequence) null, count, null);
        if (count.get(0) == 0) return false;
        VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(dev, (CharSequence) null, count, exts);
        for (int i = 0; i < exts.remaining(); i++) {
            if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(exts.get(i).extensionNameString())) return true;
        }
        return false;
    }

    /** Sets {@link #graphicsFamily} / {@link #presentFamily} as a side effect
     *  when both are found. Returns true iff both exist on this device. */
    private boolean findQueueFamiliesFor(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, count, null);
        if (count.get(0) == 0) return false;
        VkQueueFamilyProperties.Buffer fams = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, count, fams);

        int gfx = -1, pres = -1;
        IntBuffer canPresent = stack.mallocInt(1);
        for (int i = 0; i < fams.remaining(); i++) {
            if (gfx == -1 && (fams.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) gfx = i;
            vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface, canPresent);
            if (pres == -1 && canPresent.get(0) == VK_TRUE) pres = i;
            if (gfx != -1 && pres != -1) break;
        }
        if (gfx == -1 || pres == -1) return false;
        this.graphicsFamily = gfx;
        this.presentFamily  = pres;
        return true;
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int[] uniqueFamilies = (graphicsFamily == presentFamily)
                    ? new int[]{ graphicsFamily }
                    : new int[]{ graphicsFamily, presentFamily };
            VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.length, stack);
            for (int i = 0; i < uniqueFamilies.length; i++) {
                queueInfos.get(i)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(uniqueFamilies[i])
                        .pQueuePriorities(stack.floats(1.0f));
            }

            // Device extensions: swapchain always; portability_subset on macOS
            // when the device exposes it (required by the KHR spec).
            boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            int extCount = 1 + (isMac && devicePresentsPortabilitySubset(stack) ? 1 : 0);
            PointerBuffer exts = stack.mallocPointer(extCount);
            exts.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            if (extCount > 1) exts.put(stack.UTF8(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME));
            exts.flip();

            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            // Required for anisotropic texture filtering — without this,
            // walls / thin shapes at glancing angles get an over-blurred mip
            // selected (LOD picks the max-derivative axis, blurs both axes
            // equally). With anisotropy, the GPU takes multiple samples
            // along the stretched axis at a finer mip level, preserving
            // detail in the narrow axis. Universally supported on desktop.
            features.samplerAnisotropy(true);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueInfos)
                    .pEnabledFeatures(features)
                    .ppEnabledExtensionNames(exts);

            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, createInfo, null, pDevice), "vkCreateDevice");
            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            PointerBuffer pQ = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, pQ);
            graphicsQueue = new VkQueue(pQ.get(0), device);
            vkGetDeviceQueue(device, presentFamily, 0, pQ);
            presentQueue = new VkQueue(pQ.get(0), device);
        }
        Logger.info("[VK] Logical device + queues ready");
    }

    private boolean devicePresentsPortabilitySubset(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (CharSequence) null, count, null);
        if (count.get(0) == 0) return false;
        VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (CharSequence) null, count, exts);
        for (int i = 0; i < exts.remaining(); i++) {
            if (VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME.equals(exts.get(i).extensionNameString())) return true;
        }
        return false;
    }

    private void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    // RESET_COMMAND_BUFFER so we can re-record each frame.
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsFamily);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateCommandPool(device, info, null, p), "vkCreateCommandPool");
            commandPool = p.get(0);
        }
    }

    private static String deviceTypeName(int t) {
        switch (t) {
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated GPU";
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "CPU";
            default: return "other(" + t + ")";
        }
    }

    void destroy() {
        if (device != null) {
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            vkDestroyDevice(device, null);
        }
        if (instance != null) {
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            if (debugMessenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
            vkDestroyInstance(instance, null);
        }
    }
}
