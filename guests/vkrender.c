// regression guest: guest x86-64 code makes the Adreno actually execute something, and
// reads the pixels back.
//
// vulkan.c proved the thunk marshals arguments and that enumeration works. enumeration is not
// execution: nothing in it allocated device memory, built a command buffer, or waited on the
// GPU. this does all of that from guest code and then checks the bytes the GPU wrote.
//
//   create a device and a queue           the first thing that needs a real driver object graph
//   allocate two images and their memory  memory type selection out of the driver's own report
//   record clears and copies              vkCmdPipelineBarrier is ELEVEN arguments, so five of
//                                         them are read off the guest stack
//   submit, and wait on a fence           the host call blocks in the driver on a guest thread
//   map the linear image and read it      device memory addressed directly by the guest, which
//                                         only works because guest and host share one address
//                                         space 1:1
//
// it writes the result to out.ppm beside itself as well as checking it, because a test about
// graphics should produce something that can be looked at.
//
// still -nostdlib. it defines memset/memcpy itself since the compiler is entitled to emit calls
// to them for the large structures vulkan is made of, and there is no libc here to provide them.

#include <stdint.h>
#include <vulkan/vulkan_core.h>

#define WIDTH 256
#define HEIGHT 256

// --- the handful of syscalls this guest makes for itself ----------------------------------
static long Syscall3(long Number, long A, long B, long C) {
  long Result;
  __asm__ volatile("syscall" : "=a"(Result) : "a"(Number), "D"(A), "S"(B), "d"(C) : "rcx", "r11", "memory");
  return Result;
}

static long Write(int Fd, const void* Buffer, unsigned long Count) {
  return Syscall3(1, Fd, (long)Buffer, (long)Count);
}

static int Create(const char* Path) {
  // openat(AT_FDCWD, path, O_WRONLY|O_CREAT|O_TRUNC, 0644). x86-64 O_ values, which the host
  // layer translates in TranslateOpenFlags().
  long Result;
  register long R10 __asm__("r10") = 0644;
  __asm__ volatile("syscall"
                   : "=a"(Result)
                   : "a"(257L), "D"(-100L), "S"((long)Path), "d"(0x1 | 0x40 | 0x200), "r"(R10)
                   : "rcx", "r11", "memory");
  return (int)Result;
}

static void Close(int Fd) {
  Syscall3(3, Fd, 0, 0);
}

static void Exit(int Code) {
  __asm__ volatile("syscall" ::"a"(60L), "D"((long)Code) : "memory");
  __builtin_unreachable();
}

// the compiler may lower a large aggregate initialiser into a call to either of these.
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

// --- vulkan boilerplate, guest side --------------------------------------------------------

static VkPhysicalDeviceMemoryProperties MemoryProperties;

static uint32_t FindMemoryType(uint32_t TypeBits, VkMemoryPropertyFlags Wanted) {
  for (uint32_t i = 0; i < MemoryProperties.memoryTypeCount; ++i) {
    if ((TypeBits & (1u << i)) && (MemoryProperties.memoryTypes[i].propertyFlags & Wanted) == Wanted) {
      return i;
    }
  }
  return 0xFFFFFFFFu;
}

static VkResult MakeImage(VkDevice Device, VkImageTiling Tiling, VkImageUsageFlags Usage, VkMemoryPropertyFlags Wanted,
                          VkImage* OutImage, VkDeviceMemory* OutMemory) {
  VkImageCreateInfo Info;
  memset(&Info, 0, sizeof(Info));
  Info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
  Info.imageType = VK_IMAGE_TYPE_2D;
  Info.format = VK_FORMAT_R8G8B8A8_UNORM;
  Info.extent.width = WIDTH;
  Info.extent.height = HEIGHT;
  Info.extent.depth = 1;
  Info.mipLevels = 1;
  Info.arrayLayers = 1;
  Info.samples = VK_SAMPLE_COUNT_1_BIT;
  Info.tiling = Tiling;
  Info.usage = Usage;
  Info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  Info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

  VkResult Result = vkCreateImage(Device, &Info, 0, OutImage);
  if (Result != VK_SUCCESS) {
    return Result;
  }

  VkMemoryRequirements Requirements;
  vkGetImageMemoryRequirements(Device, *OutImage, &Requirements);

  VkMemoryAllocateInfo Allocate;
  memset(&Allocate, 0, sizeof(Allocate));
  Allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  Allocate.allocationSize = Requirements.size;
  Allocate.memoryTypeIndex = FindMemoryType(Requirements.memoryTypeBits, Wanted);
  if (Allocate.memoryTypeIndex == 0xFFFFFFFFu) {
    return VK_ERROR_INITIALIZATION_FAILED;
  }

  Result = vkAllocateMemory(Device, &Allocate, 0, OutMemory);
  if (Result != VK_SUCCESS) {
    return Result;
  }
  return vkBindImageMemory(Device, *OutImage, *OutMemory, 0);
}

// eleven arguments. six in registers, five off the guest stack -- by some distance the widest
// call the marshaller sees, and the reason this guest is worth having even without the pixels.
static void Barrier(VkCommandBuffer Commands, VkImage Image, VkImageLayout From, VkImageLayout To, VkAccessFlags SourceAccess,
                    VkAccessFlags DestinationAccess) {
  VkImageMemoryBarrier Change;
  memset(&Change, 0, sizeof(Change));
  Change.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
  Change.srcAccessMask = SourceAccess;
  Change.dstAccessMask = DestinationAccess;
  Change.oldLayout = From;
  Change.newLayout = To;
  Change.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  Change.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  Change.image = Image;
  Change.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  Change.subresourceRange.levelCount = 1;
  Change.subresourceRange.layerCount = 1;

  vkCmdPipelineBarrier(Commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, 0, 0, 0, 1,
                       &Change);
}

static void ClearTo(VkCommandBuffer Commands, VkImage Image, float R, float G, float B) {
  VkClearColorValue Colour;
  memset(&Colour, 0, sizeof(Colour));
  Colour.float32[0] = R;
  Colour.float32[1] = G;
  Colour.float32[2] = B;
  Colour.float32[3] = 1.0f;

  VkImageSubresourceRange Range;
  memset(&Range, 0, sizeof(Range));
  Range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  Range.levelCount = 1;
  Range.layerCount = 1;

  vkCmdClearColorImage(Commands, Image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &Colour, 1, &Range);
}

static void CopyBand(VkCommandBuffer Commands, VkImage Source, VkImage Destination, int32_t DestinationY, uint32_t Rows) {
  VkImageCopy Region;
  memset(&Region, 0, sizeof(Region));
  Region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  Region.srcSubresource.layerCount = 1;
  Region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  Region.dstSubresource.layerCount = 1;
  Region.dstOffset.y = DestinationY;
  Region.extent.width = WIDTH;
  Region.extent.height = Rows;
  Region.extent.depth = 1;

  vkCmdCopyImage(Commands, Source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, Destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                 &Region);
}

void _start(void) {
  Print("[guest] vulkan render test\n");

  VkApplicationInfo Application;
  memset(&Application, 0, sizeof(Application));
  Application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  Application.pApplicationName = "sharpemu-android render";
  Application.apiVersion = VK_API_VERSION_1_1;

  VkInstanceCreateInfo InstanceInfo;
  memset(&InstanceInfo, 0, sizeof(InstanceInfo));
  InstanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  InstanceInfo.pApplicationInfo = &Application;

  VkInstance Instance = VK_NULL_HANDLE;
  Check(vkCreateInstance(&InstanceInfo, 0, &Instance) == VK_SUCCESS, "vkCreateInstance");
  if (!Instance) {
    Exit(1);
  }

  uint32_t DeviceCount = 1;
  VkPhysicalDevice Gpu = VK_NULL_HANDLE;
  vkEnumeratePhysicalDevices(Instance, &DeviceCount, &Gpu);
  Check(Gpu != VK_NULL_HANDLE, "physical device");

  vkGetPhysicalDeviceMemoryProperties(Gpu, &MemoryProperties);
  Print("  memory types ");
  PrintNumber(MemoryProperties.memoryTypeCount);

  // a graphics queue family. the clear and the copy are transfer work, but the graphics family
  // is the one that is guaranteed to support everything here.
  uint32_t FamilyCount = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(Gpu, &FamilyCount, 0);
  VkQueueFamilyProperties Families[8];
  if (FamilyCount > 8) {
    FamilyCount = 8;
  }
  vkGetPhysicalDeviceQueueFamilyProperties(Gpu, &FamilyCount, Families);
  uint32_t Family = 0xFFFFFFFFu;
  for (uint32_t i = 0; i < FamilyCount; ++i) {
    if (Families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
      Family = i;
      break;
    }
  }
  Check(Family != 0xFFFFFFFFu, "graphics queue family");

  float Priority = 1.0f;
  VkDeviceQueueCreateInfo QueueInfo;
  memset(&QueueInfo, 0, sizeof(QueueInfo));
  QueueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
  QueueInfo.queueFamilyIndex = Family;
  QueueInfo.queueCount = 1;
  QueueInfo.pQueuePriorities = &Priority;

  VkDeviceCreateInfo DeviceInfo;
  memset(&DeviceInfo, 0, sizeof(DeviceInfo));
  DeviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  DeviceInfo.queueCreateInfoCount = 1;
  DeviceInfo.pQueueCreateInfos = &QueueInfo;

  VkDevice Device = VK_NULL_HANDLE;
  Check(vkCreateDevice(Gpu, &DeviceInfo, 0, &Device) == VK_SUCCESS, "vkCreateDevice");
  if (!Device) {
    Exit(1);
  }

  VkQueue Queue = VK_NULL_HANDLE;
  vkGetDeviceQueue(Device, Family, 0, &Queue);
  Check(Queue != VK_NULL_HANDLE, "vkGetDeviceQueue");

  VkImage Scratch = VK_NULL_HANDLE, Readback = VK_NULL_HANDLE;
  VkDeviceMemory ScratchMemory = VK_NULL_HANDLE, ReadbackMemory = VK_NULL_HANDLE;
  Check(MakeImage(Device, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &Scratch, &ScratchMemory) == VK_SUCCESS,
        "device-local image");
  Check(MakeImage(Device, VK_IMAGE_TILING_LINEAR, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                  VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &Readback,
                  &ReadbackMemory) == VK_SUCCESS,
        "host-visible readback image");
  if (Failures) {
    Exit(1);
  }

  VkCommandPoolCreateInfo PoolInfo;
  memset(&PoolInfo, 0, sizeof(PoolInfo));
  PoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
  PoolInfo.queueFamilyIndex = Family;
  VkCommandPool Pool = VK_NULL_HANDLE;
  Check(vkCreateCommandPool(Device, &PoolInfo, 0, &Pool) == VK_SUCCESS, "vkCreateCommandPool");

  VkCommandBufferAllocateInfo CommandsInfo;
  memset(&CommandsInfo, 0, sizeof(CommandsInfo));
  CommandsInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  CommandsInfo.commandPool = Pool;
  CommandsInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  CommandsInfo.commandBufferCount = 1;
  VkCommandBuffer Commands = VK_NULL_HANDLE;
  Check(vkAllocateCommandBuffers(Device, &CommandsInfo, &Commands) == VK_SUCCESS, "vkAllocateCommandBuffers");

  VkCommandBufferBeginInfo Begin;
  memset(&Begin, 0, sizeof(Begin));
  Begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  Begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  Check(vkBeginCommandBuffer(Commands, &Begin) == VK_SUCCESS, "vkBeginCommandBuffer");

  Barrier(Commands, Readback, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
          VK_ACCESS_TRANSFER_WRITE_BIT);

  // two bands, so the result says something about *where* pixels went rather than only that
  // some were written. orange on top, blue underneath.
  Barrier(Commands, Scratch, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
  ClearTo(Commands, Scratch, 1.0f, 0.4f, 0.0f);
  Barrier(Commands, Scratch, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
  CopyBand(Commands, Scratch, Readback, 0, HEIGHT / 2);

  Barrier(Commands, Scratch, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
          VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
  ClearTo(Commands, Scratch, 0.0f, 0.3f, 1.0f);
  Barrier(Commands, Scratch, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
  CopyBand(Commands, Scratch, Readback, HEIGHT / 2, HEIGHT / 2);

  Barrier(Commands, Readback, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT,
          VK_ACCESS_HOST_READ_BIT);
  Check(vkEndCommandBuffer(Commands) == VK_SUCCESS, "vkEndCommandBuffer");

  VkFenceCreateInfo FenceInfo;
  memset(&FenceInfo, 0, sizeof(FenceInfo));
  FenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
  VkFence Fence = VK_NULL_HANDLE;
  vkCreateFence(Device, &FenceInfo, 0, &Fence);

  VkSubmitInfo Submit;
  memset(&Submit, 0, sizeof(Submit));
  Submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  Submit.commandBufferCount = 1;
  Submit.pCommandBuffers = &Commands;
  Check(vkQueueSubmit(Queue, 1, &Submit, Fence) == VK_SUCCESS, "vkQueueSubmit");

  // the host call blocks inside the driver, on this guest thread, for as long as the GPU takes.
  Check(vkWaitForFences(Device, 1, &Fence, VK_TRUE, 5000000000ull) == VK_SUCCESS, "vkWaitForFences");

  VkImageSubresource Subresource;
  memset(&Subresource, 0, sizeof(Subresource));
  Subresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  VkSubresourceLayout Layout;
  vkGetImageSubresourceLayout(Device, Readback, &Subresource, &Layout);

  void* Pixels = 0;
  Check(vkMapMemory(Device, ReadbackMemory, 0, VK_WHOLE_SIZE, 0, &Pixels) == VK_SUCCESS, "vkMapMemory");
  if (!Pixels) {
    Exit(1);
  }

  const unsigned char* Base = (const unsigned char*)Pixels + Layout.offset;
  const unsigned char* Top = Base + (unsigned long)Layout.rowPitch * 10;
  const unsigned char* Bottom = Base + (unsigned long)Layout.rowPitch * (HEIGHT - 10);
  Print("  top    r,g,b = ");
  PrintNumber(Top[0]);
  Print("  ");
  PrintNumber(Top[1]);
  Print("  ");
  PrintNumber(Top[2]);
  Print("  bottom r,g,b = ");
  PrintNumber(Bottom[0]);
  Print("  ");
  PrintNumber(Bottom[1]);
  Print("  ");
  PrintNumber(Bottom[2]);

  // the clears were 1.0/0.4/0.0 and 0.0/0.3/1.0, so this is a wide tolerance on purpose: what
  // is under test is that the GPU ran and wrote where it was told, not colour precision.
  Check(Top[0] > 200 && Top[2] < 60, "top band is the first clear colour");
  Check(Bottom[2] > 200 && Bottom[0] < 60, "bottom band is the second clear colour");

  // and an artefact to actually look at.
  int File = Create("out.ppm");
  if (File >= 0) {
    char Header[64];
    const char* Text = "P6\n256 256\n255\n";
    unsigned long HeaderLength = Length(Text);
    memcpy(Header, Text, HeaderLength);
    Write(File, Header, HeaderLength);
    for (uint32_t y = 0; y < HEIGHT; ++y) {
      unsigned char Row[WIDTH * 3];
      const unsigned char* Source = Base + (unsigned long)Layout.rowPitch * y;
      for (uint32_t x = 0; x < WIDTH; ++x) {
        Row[x * 3 + 0] = Source[x * 4 + 0];
        Row[x * 3 + 1] = Source[x * 4 + 1];
        Row[x * 3 + 2] = Source[x * 4 + 2];
      }
      Write(File, Row, sizeof(Row));
    }
    Close(File);
    Print("  wrote out.ppm\n");
  } else {
    Print("  could not create out.ppm\n");
  }

  vkUnmapMemory(Device, ReadbackMemory);
  vkDestroyFence(Device, Fence, 0);
  vkDestroyCommandPool(Device, Pool, 0);
  vkDestroyImage(Device, Scratch, 0);
  vkDestroyImage(Device, Readback, 0);
  vkFreeMemory(Device, ScratchMemory, 0);
  vkFreeMemory(Device, ReadbackMemory, 0);
  vkDestroyDevice(Device, 0);
  vkDestroyInstance(Instance, 0);

  Print(Failures == 0 ? "[guest] PASS\n" : "[guest] FAIL\n");
  Exit(Failures == 0 ? 0 : 1);
}
