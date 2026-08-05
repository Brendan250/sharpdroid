#!/system/bin/sh
# the host layer's regression set. every mode must exit 0.
#
# pushed to /data/local/tmp/sharpemu/ and run there, because that is where the guests, the staged
# x86-64 glibc set and the host's own libc++_shared.so live.
cd /data/local/tmp/sharpemu || exit 1
export LD_LIBRARY_PATH=.

FAILED=0

run() {
  NAME=$1
  shift
  ./sharpemu-host-layer "$@" >./last-run.log 2>&1
  STATUS=$?
  if [ "$STATUS" -eq 0 ]; then
    echo "PASS  $NAME"
  else
    echo "FAIL  $NAME (exit $STATUS)"
    tail -n 25 ./last-run.log
    FAILED=1
  fi
}

run "M1a spike          " --spike
run "M1b hello-nostdlib " ./hello-nostdlib
run "M1b hello-libc     " ./hello-libc
run "M1c signals        " ./signals
run "M1d getent         " --libs ./guest-libs ./getent --version
run "M1g smc            " ./smc
# the default asynchronous-signal site is `syscall`, which is what boots the game (M3d). it does
# not reach the guest's spinning worker, so case 1 of this guest is expected to fail there — which
# is a real gap, recorded rather than papered over. `safepoint` is the mode that
# covers all three routes, so that is the one this asserts, and running it here is what keeps the
# interrupt-fault-page machinery from rotting while it waits to be trusted on the real workload.
run "M3d asyncsig (safepoint)" --asyncsig safepoint ./asyncsig

# the vulkan thunk. these two need a working GPU and are the only guests here that depend on
# anything outside the host layer, so a failure is worth reading before assuming a regression:
# `vulkan` checks the marshalling and the stub table, `vkrender` checks that the driver actually
# executes what the guest submits.
run "M4a vulkan          " --vulkan --libs ./guest-libs ./vulkan
run "M4b vkrender        " --vulkan --libs ./guest-libs ./vkrender
run "M4c1 vkswap         " --vulkan --libs ./guest-libs ./vkswap

# and the negative control for the thunk: without --vulkan there is no libvulkan.so.1 to be had,
# so the guest's own ld.so must fail to start it. this is what says the passes above are the
# thunk working rather than something else providing vulkan.
./sharpemu-host-layer --libs ./guest-libs ./vulkan >./last-run.log 2>&1
if [ $? -ne 0 ]; then
  echo "PASS  M4a vulkan (no --vulkan) fails as it should"
else
  echo "FAIL  M4a vulkan ran without the thunk enabled"
  FAILED=1
fi

# the audio thunk. this one makes a noise -- three seconds of a 440 Hz tone on whatever the device
# is currently routing to -- and that is deliberate: the check is that AAudioStream_getFramesRead
# advanced at the stream's own sample rate, which a stream that opened and played nothing cannot do.
run "M7a aaudio        " --audio --libs ./guest-libs ./aaudio

# and its negative control, the same shape as vulkan's: without --audio every entry point answers
# AAUDIO_ERROR_UNAVAILABLE, so AAudio_createStreamBuilder must fail and the guest must exit 1.
./sharpemu-host-layer --libs ./guest-libs ./aaudio >./last-run.log 2>&1
if [ $? -ne 0 ]; then
  echo "PASS  M7a aaudio (no --audio) fails as it should"
else
  echo "FAIL  M7a aaudio played without the thunk enabled"
  FAILED=1
fi

# and the same guest under the other two SMC modes. `full` is the fallback configuration — it
# needs no page protection at all, only InvalidateGuestCodeRange — so it has to keep working or
# there is nothing to fall back to. `none` is expected to *fail*, and is checked for failing:
# it is what says the smc guest is testing something rather than passing by accident.
run "M1g smc (full)     " --smc full ./smc

./sharpemu-host-layer --smc none ./smc >./last-run.log 2>&1
if [ $? -ne 0 ]; then
  echo "PASS  M1g smc (none) fails as it should"
else
  echo "FAIL  M1g smc (none) passed without SMC detection — the test is not testing anything"
  FAILED=1
fi

rm -f ./last-run.log
exit $FAILED
