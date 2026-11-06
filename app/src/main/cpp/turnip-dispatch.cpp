#include <android/log.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>

namespace {

// Android's Vulkan HAL ABI on arm64.
struct HardwareModule;
struct HardwareDevice {
    uint32_t tag;
    uint32_t version;
    HardwareModule * module;
    uint64_t reserved[12];
    int (*close)(HardwareDevice *);
};
struct HardwareModuleMethods {
    int (*open)(const HardwareModule *, const char *, HardwareDevice **);
};
struct HardwareModule {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char * id;
    const char * name;
    const char * author;
    HardwareModuleMethods * methods;
};
struct VulkanHardwareDevice : HardwareDevice {
    PFN_vkEnumerateInstanceExtensionProperties enumerate_extensions;
    PFN_vkCreateInstance create_instance;
    PFN_vkGetInstanceProcAddr get_instance_proc_addr;
};

VulkanHardwareDevice & driver() {
    static auto * const device = [] {
        const char * path = std::getenv("GGML_TEST_TURNIP_DRIVER");
        if (!path || path[0] != '/') {
            throw std::runtime_error("Expected an absolute Turnip driver path");
        }
        void * handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            throw std::runtime_error(std::string("Could not open Turnip: ") + dlerror());
        }
        auto * module = static_cast<HardwareModule *>(dlsym(handle, "HMI"));
        HardwareDevice * opened = nullptr;
        if (!module || !module->id || std::strcmp(module->id, "vulkan") ||
                !module->methods || !module->methods->open ||
                module->methods->open(module, "vk0", &opened) != 0 || !opened) {
            dlclose(handle);
            throw std::runtime_error("Turnip Vulkan HAL could not open vk0");
        }
        __android_log_print(ANDROID_LOG_INFO, "QwenTurnip", "Opened %s", path);
        // The GGML Vulkan backend keeps its Vulkan instance for the process lifetime.
        return static_cast<VulkanHardwareDevice *>(opened);
    }();
    return *device;
}

VkInstance active_instance = VK_NULL_HANDLE;

VKAPI_ATTR VkResult VKAPI_CALL create_instance(
        const VkInstanceCreateInfo * info, const VkAllocationCallbacks * allocator, VkInstance * instance) {
    const VkResult result = driver().create_instance(info, allocator, instance);
    if (result == VK_SUCCESS) {
        active_instance = *instance;
    }
    return result;
}

template<class Function>
Function instance_entry(const char * name) {
    auto function = reinterpret_cast<Function>(driver().get_instance_proc_addr(active_instance, name));
    if (!function) {
        throw std::runtime_error(std::string("Missing Turnip entry point: ") + name);
    }
    return function;
}

}  // namespace

extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL __wrap_vkGetInstanceProcAddr(
        VkInstance instance, const char * name) {
    if (!instance && !std::strcmp(name, "vkCreateInstance")) {
        return reinterpret_cast<PFN_vkVoidFunction>(create_instance);
    }
    if (!instance && !std::strcmp(name, "vkEnumerateInstanceExtensionProperties")) {
        return reinterpret_cast<PFN_vkVoidFunction>(driver().enumerate_extensions);
    }
    if (!std::strcmp(name, "vkGetInstanceProcAddr")) {
        return reinterpret_cast<PFN_vkVoidFunction>(__wrap_vkGetInstanceProcAddr);
    }
    return driver().get_instance_proc_addr(instance, name);
}

extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL __wrap_vkGetDeviceProcAddr(VkDevice device, const char * name) {
    return instance_entry<PFN_vkGetDeviceProcAddr>("vkGetDeviceProcAddr")(device, name);
}

extern "C" VKAPI_ATTR void VKAPI_CALL __wrap_vkGetPhysicalDeviceFeatures2(
        VkPhysicalDevice device, VkPhysicalDeviceFeatures2 * features) {
    instance_entry<PFN_vkGetPhysicalDeviceFeatures2>("vkGetPhysicalDeviceFeatures2")(device, features);
}

extern "C" VKAPI_ATTR void VKAPI_CALL __wrap_vkCmdCopyBuffer(
        VkCommandBuffer cmd, VkBuffer source, VkBuffer dest, uint32_t count, const VkBufferCopy * regions) {
    instance_entry<PFN_vkCmdCopyBuffer>("vkCmdCopyBuffer")(cmd, source, dest, count, regions);
}
