// what does vulkan look like from a bionic arm64 binary run as the shell user?
//
// this is a host-side probe only — no FEXCore, no guest. it answers the questions the vulkan thunk's
// design depends on before any of it is designed:
//   - does the platform loader hand a shell binary a working instance and device at all
//   - which instance/device extensions exist (WSI lives in the loader on android, not the
//     driver, so this is where we find out what a swapchain would need)
//   - what the driver reports itself as, so a turnip injection later is visibly different
//
// build with scripts/build-host.py --probe; it links nothing, everything is dlopen'd, which is
// also how the thunk will reach vulkan later.
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

static void *g_lib;
static PFN_vkGetInstanceProcAddr gipa;

#define LOAD_I(inst, name) PFN_##name name = (PFN_##name)gipa(inst, #name)

int main(int argc, char **argv) {
  const char *path = argc > 1 ? argv[1] : "libvulkan.so";
  g_lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!g_lib) {
    printf("FAIL dlopen(%s): %s\n", path, dlerror());
    return 1;
  }
  printf("OK   dlopen(%s)\n", path);

  gipa = (PFN_vkGetInstanceProcAddr)dlsym(g_lib, "vkGetInstanceProcAddr");
  if (!gipa) {
    printf("FAIL dlsym(vkGetInstanceProcAddr): %s\n", dlerror());
    return 1;
  }
  printf("OK   vkGetInstanceProcAddr = %p\n", (void *)gipa);

  LOAD_I(NULL, vkEnumerateInstanceVersion);
  LOAD_I(NULL, vkEnumerateInstanceExtensionProperties);
  LOAD_I(NULL, vkCreateInstance);
  if (!vkCreateInstance) {
    printf("FAIL no vkCreateInstance from the loader\n");
    return 1;
  }

  uint32_t loader_version = VK_API_VERSION_1_0;
  if (vkEnumerateInstanceVersion) {
    vkEnumerateInstanceVersion(&loader_version);
  }
  printf("     loader instance version %u.%u.%u\n", VK_VERSION_MAJOR(loader_version),
         VK_VERSION_MINOR(loader_version), VK_VERSION_PATCH(loader_version));

  uint32_t ext_count = 0;
  vkEnumerateInstanceExtensionProperties(NULL, &ext_count, NULL);
  VkExtensionProperties *exts = calloc(ext_count, sizeof(*exts));
  vkEnumerateInstanceExtensionProperties(NULL, &ext_count, exts);
  printf("     %u instance extensions:\n", ext_count);
  for (uint32_t i = 0; i < ext_count; ++i) {
    printf("       %s (rev %u)\n", exts[i].extensionName, exts[i].specVersion);
  }

  VkApplicationInfo app = {
      .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "sharpemu-android probe",
      .applicationVersion = 1,
      .pEngineName = "sharpemu-android",
      .engineVersion = 1,
      .apiVersion = loader_version,
  };
  VkInstanceCreateInfo ici = {
      .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &app,
  };
  VkInstance instance = VK_NULL_HANDLE;
  VkResult r = vkCreateInstance(&ici, NULL, &instance);
  if (r != VK_SUCCESS) {
    printf("FAIL vkCreateInstance = %d\n", r);
    return 1;
  }
  printf("OK   vkCreateInstance -> %p\n", (void *)instance);

  LOAD_I(instance, vkEnumeratePhysicalDevices);
  LOAD_I(instance, vkGetPhysicalDeviceProperties);
  LOAD_I(instance, vkGetPhysicalDeviceMemoryProperties);
  LOAD_I(instance, vkGetPhysicalDeviceQueueFamilyProperties);
  LOAD_I(instance, vkEnumerateDeviceExtensionProperties);
  LOAD_I(instance, vkCreateDevice);
  LOAD_I(instance, vkGetDeviceProcAddr);
  LOAD_I(instance, vkDestroyInstance);

  uint32_t gpu_count = 0;
  vkEnumeratePhysicalDevices(instance, &gpu_count, NULL);
  printf("     %u physical device(s)\n", gpu_count);
  if (gpu_count == 0) {
    printf("FAIL no physical devices\n");
    return 1;
  }
  VkPhysicalDevice *gpus = calloc(gpu_count, sizeof(*gpus));
  vkEnumeratePhysicalDevices(instance, &gpu_count, gpus);

  for (uint32_t i = 0; i < gpu_count; ++i) {
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(gpus[i], &props);
    printf("OK   device %u: %s\n", i, props.deviceName);
    printf("       type=%d api=%u.%u.%u driver=0x%08x vendor=0x%04x device=0x%04x\n", props.deviceType,
           VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
           VK_VERSION_PATCH(props.apiVersion), props.driverVersion, props.vendorID, props.deviceID);

    uint32_t qf_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &qf_count, NULL);
    VkQueueFamilyProperties *qfs = calloc(qf_count, sizeof(*qfs));
    vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &qf_count, qfs);
    for (uint32_t q = 0; q < qf_count; ++q) {
      printf("       queue family %u: count=%u flags=0x%x\n", q, qfs[q].queueCount, qfs[q].queueFlags);
    }
    free(qfs);

    uint32_t dev_ext_count = 0;
    vkEnumerateDeviceExtensionProperties(gpus[i], NULL, &dev_ext_count, NULL);
    VkExtensionProperties *dev_exts = calloc(dev_ext_count, sizeof(*dev_exts));
    vkEnumerateDeviceExtensionProperties(gpus[i], NULL, &dev_ext_count, dev_exts);
    printf("       %u device extensions", dev_ext_count);
    // only the ones the presenter and a future swapchain actually care about
    static const char *interesting[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,          "VK_ANDROID_native_buffer",
        "VK_EXT_queue_family_foreign",            "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_external_memory_fd",              "VK_EXT_external_memory_dma_buf",
        "VK_KHR_dedicated_allocation",            "VK_EXT_image_drm_format_modifier",
    };
    for (size_t k = 0; k < sizeof(interesting) / sizeof(*interesting); ++k) {
      for (uint32_t e = 0; e < dev_ext_count; ++e) {
        if (strcmp(dev_exts[e].extensionName, interesting[k]) == 0) {
          printf("\n       + %s", interesting[k]);
          break;
        }
      }
    }
    printf("\n");
    free(dev_exts);

    // and actually bring a device up, because "enumerates" and "works" are different claims
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = 0,
        .queueCount = 1,
        .pQueuePriorities = &prio,
    };
    VkDeviceCreateInfo dci = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &qci,
    };
    VkDevice device = VK_NULL_HANDLE;
    r = vkCreateDevice(gpus[i], &dci, NULL, &device);
    if (r != VK_SUCCESS) {
      printf("FAIL vkCreateDevice = %d\n", r);
      continue;
    }
    printf("OK   vkCreateDevice -> %p\n", (void *)device);

    PFN_vkDestroyDevice vkDestroyDevice = (PFN_vkDestroyDevice)vkGetDeviceProcAddr(device, "vkDestroyDevice");
    PFN_vkGetDeviceQueue vkGetDeviceQueue = (PFN_vkGetDeviceQueue)vkGetDeviceProcAddr(device, "vkGetDeviceQueue");
    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(device, 0, 0, &queue);
    printf("OK   vkGetDeviceQueue -> %p\n", (void *)queue);
    vkDestroyDevice(device, NULL);
  }

  vkDestroyInstance(instance, NULL);
  printf("OK   probe complete\n");
  return 0;
}
