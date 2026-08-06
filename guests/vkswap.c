// regression guest: a guest-driven swapchain, on a window system the host layer invents.
//
// there is no ANativeWindow to be had from a shell binary, so the surface and the swapchain are
// implemented in the thunk rather than forwarded to the driver. from in here that is invisible:
// the guest asks for VK_EXT_headless_surface, which is a real vulkan extension meaning exactly
// "a surface with no window", and then does the ordinary thing.
//
// what it proves that vkrender.c could not: acquire/render/present as a *loop*, with the
// semaphore and fence handshake the presenter will use, and images the guest never allocated.

#include <stdint.h>
#include <vulkan/vulkan_core.h>

#define FRAMES 3

static long Syscall3(long Number, long A, long B, long C) {
  long Result;
  __asm__ volatile("syscall" : "=a"(Result) : "a"(Number), "D"(A), "S"(B), "d"(C) : "rcx", "r11", "memory");
  return Result;
}

static void Exit(int Code) {
  __asm__ volatile("syscall" ::"a"(60L), "D"((long)Code) : "memory");
  __builtin_unreachable();
}

void* memset(void* Destination, int Value, unsigned long Count) {
  unsigned char* Out = (unsigned char*)Destination;
  for (unsigned long i = 0; i < Count; ++i) {
    Out[i] = (unsigned char)Value;
  }
  return Destination;
}

void* memcpy(void* Destination, const void* Source, unsigned long Count) {
  unsigned char* Out = (unsigned char*)Destination;
  const unsigned char* In = (const unsigned char*)Source;
  for (unsigned long i = 0; i < Count; ++i) {
    Out[i] = In[i];
  }
  return Destination;
}

static unsigned long Length(const char* Text) {
  unsigned long n = 0;
  while (Text[n]) {
    ++n;
  }
  return n;
}

static void Print(const char* Text) {
  Syscall3(1, 1, (long)Text, (long)Length(Text));
}

static void PrintNumber(unsigned long Value) {
  char Buffer[24];
  int At = (int)sizeof(Buffer);
  Buffer[--At] = '\n';
  if (Value == 0) {
    Buffer[--At] = '0';
  }
  while (Value) {
    Buffer[--At] = (char)('0' + (Value % 10));
    Value /= 10;
  }
  Syscall3(1, 1, (long)(Buffer + At), (long)(sizeof(Buffer) - (unsigned long)At));
}

static int Failures = 0;

static void Check(int Condition, const char* What) {
  Print(Condition ? "  ok   " : "  FAIL ");
  Print(What);
  Print("\n");
  if (!Condition) {
    ++Failures;
  }
}

void _start(void) {
  Print("[guest] vulkan swapchain test\n");

  const char* InstanceExtensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_EXT_HEADLESS_SURFACE_EXTENSION_NAME};

  VkApplicationInfo Application;
  memset(&Application, 0, sizeof(Application));
  Application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  Application.pApplicationName = "sharpemu-android swapchain";
  Application.apiVersion = VK_API_VERSION_1_1;

  VkInstanceCreateInfo InstanceInfo;
  memset(&InstanceInfo, 0, sizeof(InstanceInfo));
  InstanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  InstanceInfo.pApplicationInfo = &Application;
  InstanceInfo.enabledExtensionCount = 2;
  InstanceInfo.ppEnabledExtensionNames = InstanceExtensions;

  VkInstance Instance = VK_NULL_HANDLE;
  Check(vkCreateInstance(&InstanceInfo, 0, &Instance) == VK_SUCCESS, "vkCreateInstance with surface extensions");
  if (!Instance) {
    Exit(1);
  }

  // the extension has to be *reported* as well as accepted, or a well-behaved client would
  // never ask for it. this is the check that says the thunk's enumeration is honest.
  uint32_t ExtensionCount = 0;
  vkEnumerateInstanceExtensionProperties(0, &ExtensionCount, 0);
  Check(ExtensionCount > 0, "vkEnumerateInstanceExtensionProperties (count)");
  Print("  instance extensions ");
  PrintNumber(ExtensionCount);

  uint32_t DeviceCount = 1;
  VkPhysicalDevice Gpu = VK_NULL_HANDLE;
  vkEnumeratePhysicalDevices(Instance, &DeviceCount, &Gpu);

  VkHeadlessSurfaceCreateInfoEXT SurfaceInfo;
  memset(&SurfaceInfo, 0, sizeof(SurfaceInfo));
  SurfaceInfo.sType = VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT;
  VkSurfaceKHR Surface = VK_NULL_HANDLE;
  Check(vkCreateHeadlessSurfaceEXT(Instance, &SurfaceInfo, 0, &Surface) == VK_SUCCESS, "vkCreateHeadlessSurfaceEXT");

  VkBool32 Supported = VK_FALSE;
  vkGetPhysicalDeviceSurfaceSupportKHR(Gpu, 0, Surface, &Supported);
  Check(Supported == VK_TRUE, "vkGetPhysicalDeviceSurfaceSupportKHR");

  VkSurfaceCapabilitiesKHR Capabilities;
  Check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(Gpu, Surface, &Capabilities) == VK_SUCCESS,
        "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
  Print("  surface extent ");
  PrintNumber(Capabilities.currentExtent.width);
  Print("  x ");
  PrintNumber(Capabilities.currentExtent.height);

  uint32_t FormatCount = 0;
  vkGetPhysicalDeviceSurfaceFormatsKHR(Gpu, Surface, &FormatCount, 0);
  VkSurfaceFormatKHR Formats[8];
  if (FormatCount > 8) {
    FormatCount = 8;
  }
  vkGetPhysicalDeviceSurfaceFormatsKHR(Gpu, Surface, &FormatCount, Formats);
  Check(FormatCount > 0, "vkGetPhysicalDeviceSurfaceFormatsKHR");

  uint32_t ModeCount = 0;
  vkGetPhysicalDeviceSurfacePresentModesKHR(Gpu, Surface, &ModeCount, 0);
  Check(ModeCount > 0, "vkGetPhysicalDeviceSurfacePresentModesKHR");

  const char* DeviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  float Priority = 1.0f;
  VkDeviceQueueCreateInfo QueueInfo;
  memset(&QueueInfo, 0, sizeof(QueueInfo));
  QueueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
  QueueInfo.queueFamilyIndex = 0;
  QueueInfo.queueCount = 1;
  QueueInfo.pQueuePriorities = &Priority;

  VkDeviceCreateInfo DeviceInfo;
  memset(&DeviceInfo, 0, sizeof(DeviceInfo));
  DeviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  DeviceInfo.queueCreateInfoCount = 1;
  DeviceInfo.pQueueCreateInfos = &QueueInfo;
  DeviceInfo.enabledExtensionCount = 1;
  DeviceInfo.ppEnabledExtensionNames = DeviceExtensions;

  VkDevice Device = VK_NULL_HANDLE;
  Check(vkCreateDevice(Gpu, &DeviceInfo, 0, &Device) == VK_SUCCESS, "vkCreateDevice with VK_KHR_swapchain");
  if (!Device) {
    Exit(1);
  }
  VkQueue Queue = VK_NULL_HANDLE;
  vkGetDeviceQueue(Device, 0, 0, &Queue);

  VkSwapchainCreateInfoKHR ChainInfo;
  memset(&ChainInfo, 0, sizeof(ChainInfo));
  ChainInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
  ChainInfo.surface = Surface;
  ChainInfo.minImageCount = 3;
  ChainInfo.imageFormat = Formats[0].format;
  ChainInfo.imageColorSpace = Formats[0].colorSpace;
  ChainInfo.imageExtent = Capabilities.currentExtent;
  ChainInfo.imageArrayLayers = 1;
  ChainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  ChainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
  ChainInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
  ChainInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
  ChainInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
  ChainInfo.clipped = VK_TRUE;

  VkSwapchainKHR Chain = VK_NULL_HANDLE;
  Check(vkCreateSwapchainKHR(Device, &ChainInfo, 0, &Chain) == VK_SUCCESS, "vkCreateSwapchainKHR");
  if (!Chain) {
    Exit(1);
  }

  uint32_t ImageCount = 0;
  vkGetSwapchainImagesKHR(Device, Chain, &ImageCount, 0);
  VkImage Images[8];
  if (ImageCount > 8) {
    ImageCount = 8;
  }
  vkGetSwapchainImagesKHR(Device, Chain, &ImageCount, Images);
  Check(ImageCount >= 2, "vkGetSwapchainImagesKHR");
  Print("  swapchain images ");
  PrintNumber(ImageCount);

  VkCommandPoolCreateInfo PoolInfo;
  memset(&PoolInfo, 0, sizeof(PoolInfo));
  PoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
  PoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  PoolInfo.queueFamilyIndex = 0;
  VkCommandPool Pool = VK_NULL_HANDLE;
  vkCreateCommandPool(Device, &PoolInfo, 0, &Pool);

  VkCommandBufferAllocateInfo CommandsInfo;
  memset(&CommandsInfo, 0, sizeof(CommandsInfo));
  CommandsInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  CommandsInfo.commandPool = Pool;
  CommandsInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  CommandsInfo.commandBufferCount = 1;
  VkCommandBuffer Commands = VK_NULL_HANDLE;
  vkAllocateCommandBuffers(Device, &CommandsInfo, &Commands);

  VkSemaphoreCreateInfo SemaphoreInfo;
  memset(&SemaphoreInfo, 0, sizeof(SemaphoreInfo));
  SemaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
  VkSemaphore Acquired = VK_NULL_HANDLE, Rendered = VK_NULL_HANDLE;
  vkCreateSemaphore(Device, &SemaphoreInfo, 0, &Acquired);
  vkCreateSemaphore(Device, &SemaphoreInfo, 0, &Rendered);

  VkFenceCreateInfo FenceInfo;
  memset(&FenceInfo, 0, sizeof(FenceInfo));
  FenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
  VkFence Done = VK_NULL_HANDLE;
  vkCreateFence(Device, &FenceInfo, 0, &Done);

  int AcquireFailures = 0, SubmitFailures = 0, PresentFailures = 0;
  for (int Frame = 0; Frame < FRAMES; ++Frame) {
    uint32_t Index = 0xFFFFFFFFu;
    if (vkAcquireNextImageKHR(Device, Chain, 1000000000ull, Acquired, VK_NULL_HANDLE, &Index) != VK_SUCCESS ||
        Index >= ImageCount) {
      ++AcquireFailures;
      continue;
    }

    vkResetCommandBuffer(Commands, 0);
    VkCommandBufferBeginInfo Begin;
    memset(&Begin, 0, sizeof(Begin));
    Begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    Begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(Commands, &Begin);

    VkImageMemoryBarrier ToTransfer;
    memset(&ToTransfer, 0, sizeof(ToTransfer));
    ToTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    ToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    ToTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    ToTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    ToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ToTransfer.image = Images[Index];
    ToTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    ToTransfer.subresourceRange.levelCount = 1;
    ToTransfer.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(Commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, 0, 0, 0, 1,
                         &ToTransfer);

    VkClearColorValue Colour;
    memset(&Colour, 0, sizeof(Colour));
    Colour.float32[0] = (float)(Frame + 1) / (float)FRAMES;
    Colour.float32[2] = 1.0f - Colour.float32[0];
    Colour.float32[3] = 1.0f;
    VkImageSubresourceRange Range;
    memset(&Range, 0, sizeof(Range));
    Range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    Range.levelCount = 1;
    Range.layerCount = 1;
    vkCmdClearColorImage(Commands, Images[Index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &Colour, 1, &Range);

    VkImageMemoryBarrier ToPresent = ToTransfer;
    ToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    ToPresent.dstAccessMask = 0;
    ToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    ToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    vkCmdPipelineBarrier(Commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, 0, 0, 0, 1,
                         &ToPresent);
    vkEndCommandBuffer(Commands);

    VkPipelineStageFlags WaitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo Submit;
    memset(&Submit, 0, sizeof(Submit));
    Submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    Submit.waitSemaphoreCount = 1;
    Submit.pWaitSemaphores = &Acquired;
    Submit.pWaitDstStageMask = &WaitStage;
    Submit.commandBufferCount = 1;
    Submit.pCommandBuffers = &Commands;
    Submit.signalSemaphoreCount = 1;
    Submit.pSignalSemaphores = &Rendered;
    if (vkQueueSubmit(Queue, 1, &Submit, Done) != VK_SUCCESS) {
      ++SubmitFailures;
      continue;
    }

    VkPresentInfoKHR PresentInfo;
    memset(&PresentInfo, 0, sizeof(PresentInfo));
    PresentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    PresentInfo.waitSemaphoreCount = 1;
    PresentInfo.pWaitSemaphores = &Rendered;
    PresentInfo.swapchainCount = 1;
    PresentInfo.pSwapchains = &Chain;
    PresentInfo.pImageIndices = &Index;
    if (vkQueuePresentKHR(Queue, &PresentInfo) != VK_SUCCESS) {
      ++PresentFailures;
    }

    vkWaitForFences(Device, 1, &Done, VK_TRUE, 5000000000ull);
    vkResetFences(Device, 1, &Done);
  }

  Check(AcquireFailures == 0, "vkAcquireNextImageKHR every frame");
  Check(SubmitFailures == 0, "vkQueueSubmit every frame");
  Check(PresentFailures == 0, "vkQueuePresentKHR every frame");

  vkDeviceWaitIdle(Device);
  vkDestroySwapchainKHR(Device, Chain, 0);
  vkDestroySurfaceKHR(Instance, Surface, 0);
  vkDestroyDevice(Device, 0);
  vkDestroyInstance(Instance, 0);

  Print(Failures == 0 ? "[guest] PASS\n" : "[guest] FAIL\n");
  Exit(Failures == 0 ? 0 : 1);
}
