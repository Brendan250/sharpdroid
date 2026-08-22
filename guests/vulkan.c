// regression guest: guest x86-64 vulkan through the host layer's thunk onto the real driver.
//
// unlike the other guests this one is *dynamic* and links against nothing but the generated
// libvulkan.so.1, so the whole chain is under test rather than just the marshaller:
//
//   the guest's own ld.so finds libvulkan.so.1 on LD_LIBRARY_PATH
//     -> runs its .init_array, which hands the host the stub table address
//       -> a PLT call lands in a 16-byte stub, which is a syscall with a magic number
//         -> the host reads the arguments out of the spilled CPUState and calls the arm64 driver
//
// still -nostdlib, for the same reason every other guest here is: a libc in between would decide
// half of what is being tested. the only syscalls it makes on its own account are write and exit.
//
// what it checks, in order, and why each one is here rather than being covered by the one before:
//   1. the library loads at all and attach ran           the .so is well-formed to glibc's loader
//   2. vkCreateInstance                                  pointer arguments, and a VkResult back
//   3. vkEnumeratePhysicalDevices                        an out-count, then an out-array
//   4. vkGetPhysicalDeviceProperties                     a 1 KiB struct filled in place, no copy
//   5. vkGetPhysicalDeviceImageFormatProperties          SEVEN integer arguments, so the last one
//                                                        is passed on the guest stack rather than
//                                                        in a register. nothing above reaches it
//   6. vkGetInstanceProcAddr, then a call through it     the returned address must be a *guest*
//                                                        stub, not the host function
//
// what it does NOT check: float arguments. every vulkan command taking one by value is a command
// buffer recording call (vkCmdSetLineWidth, vkCmdSetDepthBias), so the SSE half of the argument
// classification only gets exercised once there is something to record into. that is vkrender.c.

#include <stdint.h>
#include <vulkan/vulkan_core.h>

// --- the only two syscalls this guest makes for itself ------------------------------------
static long Write(int Fd, const void* Buffer, unsigned long Length) {
  long Result;
  __asm__ volatile("syscall" : "=a"(Result) : "a"(1L), "D"((long)Fd), "S"(Buffer), "d"(Length) : "rcx", "r11", "memory");
  return Result;
}

static void Exit(int Code) {
  __asm__ volatile("syscall" ::"a"(60L), "D"((long)Code) : "memory");
  __builtin_unreachable();
}

static unsigned long Length(const char* Text) {
  unsigned long n = 0;
  while (Text[n]) {
    ++n;
  }
  return n;
}

static void Print(const char* Text) {
  Write(1, Text, Length(Text));
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
  Write(1, Buffer + At, sizeof(Buffer) - (unsigned long)At);
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
  Print("[guest] vulkan thunk test\n");

  // 1. the library is loaded by now, or we would not have got here at all: every vk symbol
  //    below is a PLT entry the guest's ld.so had to resolve before _start ran.
  uint32_t ApiVersion = 0;
  VkResult Result = vkEnumerateInstanceVersion(&ApiVersion);
  Check(Result == VK_SUCCESS, "vkEnumerateInstanceVersion");
  Print("  instance version ");
  PrintNumber(VK_VERSION_MAJOR(ApiVersion));
  Print("  minor ");
  PrintNumber(VK_VERSION_MINOR(ApiVersion));

  // 2. a struct the guest built on its own stack, read by the driver in place. this is the
  //    whole 1:1 address space argument in one call: no copy, no translation, no shadow.
  VkApplicationInfo Application = {
    .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
    .pApplicationName = "sharpdroid guest",
    .applicationVersion = 1,
    .pEngineName = "sharpdroid",
    .engineVersion = 1,
    .apiVersion = VK_API_VERSION_1_1,
  };
  VkInstanceCreateInfo InstanceInfo = {
    .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
    .pApplicationInfo = &Application,
  };
  VkInstance Instance = VK_NULL_HANDLE;
  Result = vkCreateInstance(&InstanceInfo, 0, &Instance);
  Check(Result == VK_SUCCESS && Instance != VK_NULL_HANDLE, "vkCreateInstance");
  if (Result != VK_SUCCESS) {
    Print("[guest] cannot continue without an instance\n");
    Exit(1);
  }

  // 3. the two-call idiom: ask for the count, then ask again with room for the answer.
  uint32_t DeviceCount = 0;
  Result = vkEnumeratePhysicalDevices(Instance, &DeviceCount, 0);
  Check(Result == VK_SUCCESS && DeviceCount > 0, "vkEnumeratePhysicalDevices (count)");
  Print("  physical devices ");
  PrintNumber(DeviceCount);

  VkPhysicalDevice Devices[8];
  if (DeviceCount > 8) {
    DeviceCount = 8;
  }
  Result = vkEnumeratePhysicalDevices(Instance, &DeviceCount, Devices);
  Check(Result == VK_SUCCESS, "vkEnumeratePhysicalDevices (array)");
  if (DeviceCount == 0) {
    Exit(1);
  }

  // 4. a large struct written back into guest memory by the driver.
  VkPhysicalDeviceProperties Properties;
  vkGetPhysicalDeviceProperties(Devices[0], &Properties);
  Check(Properties.deviceName[0] != 0, "vkGetPhysicalDeviceProperties");
  Print("  device: ");
  Print(Properties.deviceName);
  Print("\n  driver api major ");
  PrintNumber(VK_VERSION_MAJOR(Properties.apiVersion));
  Print("  minor ");
  PrintNumber(VK_VERSION_MINOR(Properties.apiVersion));

  // 5. seven integer arguments. six fit in registers under SysV; pImageFormatProperties is the
  //    seventh and is passed on the guest's stack, so this is the only check here that says
  //    anything about the host reading past the register file. a wrong stack cursor writes the
  //    result somewhere else entirely and maxExtent stays zero.
  VkImageFormatProperties FormatProperties;
  for (unsigned long i = 0; i < sizeof(FormatProperties); ++i) {
    ((volatile unsigned char*)&FormatProperties)[i] = 0;
  }
  Result = vkGetPhysicalDeviceImageFormatProperties(
    Devices[0], VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL,
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 0, &FormatProperties);
  Check(Result == VK_SUCCESS && FormatProperties.maxExtent.width > 0,
        "vkGetPhysicalDeviceImageFormatProperties (7th argument on the stack)");
  Print("  max 2D extent ");
  PrintNumber(FormatProperties.maxExtent.width);

  // 6. and the one return value that is an address the guest will jump to. it has to be a stub
  //    in the guest's own libvulkan.so.1; the host function behind it is arm64.
  PFN_vkVoidFunction Resolved = vkGetInstanceProcAddr(Instance, "vkDestroyInstance");
  Check(Resolved != 0, "vkGetInstanceProcAddr(vkDestroyInstance)");
  PFN_vkVoidFunction Missing = vkGetInstanceProcAddr(Instance, "vkNotARealCommandXYZ");
  Check(Missing == 0, "vkGetInstanceProcAddr(nonsense) returns null");

  if (Resolved) {
    // called through the returned pointer rather than through the PLT, which is the point.
    ((PFN_vkDestroyInstance)Resolved)(Instance, 0);
    Check(1, "call through the resolved address");
  }

  Print(Failures == 0 ? "[guest] PASS\n" : "[guest] FAIL\n");
  Exit(Failures == 0 ? 0 : 1);
}
