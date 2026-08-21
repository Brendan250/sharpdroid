// regression guest: guest x86-64 AAudio through the host layer's thunk onto the real device.
//
// this is vulkan.c's sibling and it is dynamic for the same reason -- it links against nothing but
// the generated libaaudio.so, so the whole chain is under test rather than just the marshaller:
//
//   the guest's own ld.so finds libaaudio.so on LD_LIBRARY_PATH
//     -> a PLT call lands in a 16-byte stub, which is a syscall with a magic number
//       -> the host reads the arguments out of the spilled CPUState and calls the real AAudio
//
// there is no .init_array and no attach call, unlike vulkan: AAudio has no procedure-address API,
// so the guest's ld.so resolving these names is the entire mechanism.
//
// still -nostdlib, for the same reason every other guest here is. that also means no libm, which
// is why the tone comes out of a two-term recurrence rather than out of sinf(): a digital
// resonator, s[n+1] = 2*cos(w)*s[n] - s[n-1], seeded with sin(w) and stable to about 1e-10 over
// the couple of seconds this runs for.
//
// **what makes this a test rather than a demonstration** is the last check. a stream that opens
// and plays nothing succeeds at every call and accepts every buffer; the only thing that differs
// is that the device never consumed anything. so the pass condition is AAudioStream_getFramesRead
// advancing at the stream's own sample rate against a clock the guest reads itself. it is the same
// guard a GPU driver package needs against failing to load and falling back to the platform's own:
// without it, a run that silently does nothing reports a splendid number.

#include <stdint.h>

// declared here rather than by including <aaudio/AAudio.h>, because the header is not the thing
// under test and dragging it in would drag in its availability attributes with it. these are the
// twelve entry points a minimal playing stream needs, and they are the same twelve the fork's
// AndroidHostAudio will P/Invoke.
typedef struct AAudioStreamBuilder AAudioStreamBuilder;
typedef struct AAudioStream AAudioStream;
typedef void (*AAudioStream_errorCallback)(AAudioStream*, void*, int32_t);

extern int32_t AAudio_createStreamBuilder(AAudioStreamBuilder** builder);
extern void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder* builder, int32_t sampleRate);
extern void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder* builder, int32_t channelCount);
extern void AAudioStreamBuilder_setFormat(AAudioStreamBuilder* builder, int32_t format);
extern void AAudioStreamBuilder_setDirection(AAudioStreamBuilder* builder, int32_t direction);
extern void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder* builder, int32_t mode);
extern void AAudioStreamBuilder_setUsage(AAudioStreamBuilder* builder, int32_t usage);
extern void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder* builder, AAudioStream_errorCallback callback,
                                                 void* userData);
extern int32_t AAudioStreamBuilder_openStream(AAudioStreamBuilder* builder, AAudioStream** stream);
extern int32_t AAudioStreamBuilder_delete(AAudioStreamBuilder* builder);
extern int32_t AAudioStream_requestStart(AAudioStream* stream);
extern int32_t AAudioStream_requestStop(AAudioStream* stream);
extern int32_t AAudioStream_close(AAudioStream* stream);
extern int32_t AAudioStream_write(AAudioStream* stream, const void* buffer, int32_t numFrames, int64_t timeoutNanos);
extern int32_t AAudioStream_getSampleRate(AAudioStream* stream);
extern int32_t AAudioStream_getChannelCount(AAudioStream* stream);
extern int32_t AAudioStream_getFormat(AAudioStream* stream);
extern int32_t AAudioStream_getFramesPerBurst(AAudioStream* stream);
extern int32_t AAudioStream_getXRunCount(AAudioStream* stream);
extern int64_t AAudioStream_getFramesRead(AAudioStream* stream);
extern int64_t AAudioStream_getFramesWritten(AAudioStream* stream);
extern const char* AAudio_convertResultToText(int32_t result);

// the handful of constants that go with them, copied from aaudio/AAudio.h.
#define AAUDIO_OK 0
#define AAUDIO_DIRECTION_OUTPUT 0
#define AAUDIO_FORMAT_PCM_FLOAT 2
#define AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12
#define AAUDIO_USAGE_GAME 14

// --- the only syscalls this guest makes for itself ------------------------------------------
static long Write(int Fd, const void* Buffer, unsigned long Length) {
  long Result;
  __asm__ volatile("syscall" : "=a"(Result) : "a"(1L), "D"((long)Fd), "S"(Buffer), "d"(Length) : "rcx", "r11", "memory");
  return Result;
}

static void Exit(int Code) {
  __asm__ volatile("syscall" ::"a"(60L), "D"((long)Code) : "memory");
  __builtin_unreachable();
}

struct GuestTimespec {
  long Seconds;
  long Nanos;
};

// CLOCK_MONOTONIC, read by the guest itself. this is what makes the frames-read check a rate
// rather than a total: the host layer's own report uses the host's clock, and a check that trusted
// it would be checking the host against itself.
static double Now(void) {
  struct GuestTimespec Time = {0, 0};
  __asm__ volatile("syscall" ::"a"(228L), "D"(1L), "S"(&Time) : "rcx", "r11", "memory");
  return (double)Time.Seconds + (double)Time.Nanos / 1e9;
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

static void PrintNumber(long Value) {
  char Buffer[24];
  int At = (int)sizeof(Buffer);
  int Negative = Value < 0;
  unsigned long Magnitude = Negative ? (unsigned long)(-Value) : (unsigned long)Value;
  Buffer[--At] = '\n';
  if (Magnitude == 0) {
    Buffer[--At] = '0';
  }
  while (Magnitude) {
    Buffer[--At] = (char)('0' + (Magnitude % 10));
    Magnitude /= 10;
  }
  if (Negative) {
    Buffer[--At] = '-';
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

// 440 Hz at 48000 Hz: w = 2*pi*440/48000. both constants are the exact doubles, so the guest needs
// no trigonometry and no libm. if the stream negotiates a different rate the tone comes out at a
// different pitch, which is harmless and is not what is being measured.
#define TONE_COS_W 0.99834152290568207
#define TONE_SIN_W 0.057564027370409281

#define CHUNK_FRAMES 256
#define TARGET_SECONDS 3
// long enough that android's audio server gives up on a silent client -- it suspends the stream
// after a few hundred milliseconds of a full up-message queue -- and short enough that a run stays
// a regression test rather than a soak.
#define PAUSE_MS 1500
#define RESUME_SECONDS 2

static void Sleep(long Milliseconds) {
  struct GuestTimespec Request = {Milliseconds / 1000, (Milliseconds % 1000) * 1000000L};
  __asm__ volatile("syscall" ::"a"(35L), "D"(&Request), "S"(0L) : "rcx", "r11", "memory");
}

// one phase of tone. the oscillator state is static so a second phase continues the waveform
// rather than restarting it, which keeps the resume from being an audible click of its own.
static double OscPrevious = 0.0;
static double OscCurrent = TONE_SIN_W;

static void Play(AAudioStream* Stream, int32_t Rate, int32_t Channels, int32_t TotalFrames, int32_t* ShortWrites,
                 int32_t* Failures_) {
  float Chunk[CHUNK_FRAMES * 8];
  int32_t Written = 0;
  (void)Rate;

  while (Written < TotalFrames) {
    int32_t Frames = TotalFrames - Written;
    if (Frames > CHUNK_FRAMES) {
      Frames = CHUNK_FRAMES;
    }
    for (int32_t i = 0; i < Frames; ++i) {
      const double Next = 2.0 * TONE_COS_W * OscCurrent - OscPrevious;
      OscPrevious = OscCurrent;
      OscCurrent = Next;
      const float Sample = (float)(0.2 * OscPrevious);
      for (int32_t c = 0; c < Channels && c < 8; ++c) {
        Chunk[i * Channels + c] = Sample;
      }
    }

    int32_t Offset = 0;
    int32_t Attempts = 0;
    while (Offset < Frames) {
      const int32_t Accepted =
        AAudioStream_write(Stream, Chunk + (long)Offset * Channels, Frames - Offset, 20000000LL);
      if (Accepted < 0) {
        if (*Failures_ == 0) {
          Print("  write failed: ");
          Print(AAudio_convertResultToText(Accepted));
          Print("\n");
        }
        ++*Failures_;
        return;
      }
      if (Accepted < Frames - Offset) {
        ++*ShortWrites;
      }
      Offset += Accepted;
      // a stream that accepts nothing forever would spin here otherwise, and a spinning test is a
      // hang rather than a failure.
      if (Accepted == 0 && ++Attempts > 50) {
        return;
      }
    }
    Written += Frames;
  }
}

void _start(void) {
  Print("[guest] aaudio thunk test\n");

  // 1. the first call decides whether there is a thunk at all, and it has to be one that returns
  //    a *result code* rather than a pointer. without --audio the host answers every entry point
  //    with AAUDIO_ERROR_UNAVAILABLE, and a const char* command answering that way hands the guest
  //    -889 as an address -- so the negative control has to fail here, cleanly, and not four lines
  //    later in a segfault. (it did, once, which is how this ordering was arrived at.)
  AAudioStreamBuilder* Builder = 0;
  int32_t Result = AAudio_createStreamBuilder(&Builder);
  Check(Result == AAUDIO_OK && Builder != 0, "AAudio_createStreamBuilder");
  if (Result != AAUDIO_OK || !Builder) {
    Print("[guest] no AAudio: the thunk is not enabled, or the device refused a stream builder\n");
    Print("[guest] FAIL\n");
    Exit(1);
  }

  // 2. a const char* return: a host pointer the guest then dereferences, in place, with no
  //    translation. this is the 1:1 address space in one call.
  const char* Text = AAudio_convertResultToText(AAUDIO_OK);
  Check(Text != 0 && Text[0] != 0, "AAudio_convertResultToText returns a readable host string");
  if (Text && Text[0]) {
    Print("  AAUDIO_OK is \"");
    Print(Text);
    Print("\"\n");
  }

  AAudioStreamBuilder_setSampleRate(Builder, 48000);
  AAudioStreamBuilder_setChannelCount(Builder, 2);
  AAudioStreamBuilder_setFormat(Builder, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setDirection(Builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setPerformanceMode(Builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setUsage(Builder, AAUDIO_USAGE_GAME);

  // 3. the thunk's documented boundary, asked for on purpose. a callback setter is refused by the
  //    host half and says so, because honouring it would mean a driver-owned arm64 thread entering
  //    guest x86-64 code. null is a legal AAudio argument meaning "no callback", so the refusal
  //    changes nothing the guest can observe -- which is the point: the stream below still opens.
  AAudioStreamBuilder_setErrorCallback(Builder, 0, 0);

  AAudioStream* Stream = 0;
  Result = AAudioStreamBuilder_openStream(Builder, &Stream);
  Check(Result == AAUDIO_OK && Stream != 0, "AAudioStreamBuilder_openStream");
  AAudioStreamBuilder_delete(Builder);
  if (Result != AAUDIO_OK || !Stream) {
    Print("  openStream said: ");
    Print(AAudio_convertResultToText(Result));
    Print("\n[guest] FAIL\n");
    Exit(1);
  }

  // 4. what the device actually gave us, which need not be what was asked for. AAudio negotiates,
  //    and a guest that assumes otherwise writes stereo float into a mono int16 stream.
  const int32_t Rate = AAudioStream_getSampleRate(Stream);
  const int32_t Channels = AAudioStream_getChannelCount(Stream);
  const int32_t Format = AAudioStream_getFormat(Stream);
  const int32_t Burst = AAudioStream_getFramesPerBurst(Stream);
  Print("  negotiated rate ");
  PrintNumber(Rate);
  Print("  channels ");
  PrintNumber(Channels);
  Print("  format ");
  PrintNumber(Format);
  Print("  burst ");
  PrintNumber(Burst);
  Check(Rate > 0 && Channels > 0, "the stream reports a usable rate and channel count");
  Check(Format == AAUDIO_FORMAT_PCM_FLOAT, "the stream is float32, as asked");

  Result = AAudioStream_requestStart(Stream);
  Check(Result == AAUDIO_OK, "AAudioStream_requestStart");

  // 5. the tone. one chunk at a time, retrying short writes -- the host clamps the guest's timeout
  //    so that a parked guest thread cannot stall a garbage collection, which means a short write
  //    is the normal case rather than an error.
  //
  //    `Play` is a function rather than a loop because phase 7 runs it a second time.
  int32_t ShortWrites = 0;
  int32_t WriteFailures = 0;
  double Started = Now();
  Play(Stream, Rate, Channels, Rate * TARGET_SECONDS, &ShortWrites, &WriteFailures);

  double Elapsed = Now() - Started;
  int64_t FramesRead = AAudioStream_getFramesRead(Stream);
  int64_t FramesWritten = AAudioStream_getFramesWritten(Stream);

  Print("  frames written ");
  PrintNumber((long)FramesWritten);
  Print("  frames read ");
  PrintNumber((long)FramesRead);
  Print("  short writes ");
  PrintNumber(ShortWrites);
  Print("  xruns ");
  PrintNumber(AAudioStream_getXRunCount(Stream));
  Print("  elapsed ms ");
  PrintNumber((long)(Elapsed * 1000.0));

  // 6. **the one that matters.** the device has to have consumed those frames at the stream's own
  //    rate. a stream that opened and played nothing passes every check above and fails this one.
  //    the window is wide on purpose: the point is to separate "playing" from "not playing at all",
  //    not to measure clock drift.
  double Expected = Elapsed * (double)Rate;
  double Ratio = Expected > 0.0 ? (double)FramesRead / Expected : 0.0;
  Print("  frames read as a percentage of rate x elapsed ");
  PrintNumber((long)(Ratio * 100.0));
  Check(WriteFailures == 0, "no write failed");
  Check(Ratio > 0.8 && Ratio < 1.2, "getFramesRead advanced at the stream's sample rate");

  // 7. **the guest goes quiet, and audio has to survive it.**
  //
  //    this is the audio stall reproduced deliberately. a real guest is not punctual: a garbage
  //    collection, a long frame or the scheduler can leave the audio thread not calling AAudio for
  //    a second or more. android's audio server pushes messages *up* to the client and the client
  //    only drains that queue from inside its own AAudio calls -- so a client that goes quiet gets
  //    "writeUpMessageQueue(): Queue full. Did client stop? Suspending stream" and the stream is
  //    dead, permanently and with nothing returning an error.
  //
  //    so: stop calling AAudio entirely for PAUSE_MS, then play again and require that the device
  //    still consumes it. a backend that cannot survive this is a backend that loses audio a third
  //    of the way into a real game, which is exactly what happened.
  Print("  going quiet for ");
  PrintNumber(PAUSE_MS);
  Sleep(PAUSE_MS);

  const int64_t ReadBeforeResume = AAudioStream_getFramesRead(Stream);
  Started = Now();
  ShortWrites = 0;
  WriteFailures = 0;
  Play(Stream, Rate, Channels, Rate * RESUME_SECONDS, &ShortWrites, &WriteFailures);
  Elapsed = Now() - Started;

  FramesRead = AAudioStream_getFramesRead(Stream) - ReadBeforeResume;
  Print("  after the pause: frames read ");
  PrintNumber((long)FramesRead);
  Print("  write failures ");
  PrintNumber(WriteFailures);
  Print("  elapsed ms ");
  PrintNumber((long)(Elapsed * 1000.0));

  Expected = Elapsed * (double)Rate;
  Ratio = Expected > 0.0 ? (double)FramesRead / Expected : 0.0;
  Print("  resumed percentage ");
  PrintNumber((long)(Ratio * 100.0));
  Check(WriteFailures == 0, "no write failed after the pause");
  Check(Ratio > 0.8 && Ratio < 1.2, "the stream survives the guest going quiet");

  AAudioStream_requestStop(Stream);
  AAudioStream_close(Stream);

  Print(Failures == 0 ? "[guest] PASS\n" : "[guest] FAIL\n");
  Exit(Failures == 0 ? 0 : 1);
}
