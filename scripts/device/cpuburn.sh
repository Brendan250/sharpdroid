#!/system/bin/sh
# the control: eight cores of sustained integer work with none of the host layer in it.
#
# if this reboots the device then nothing about the emulator is implicated and the answer is the
# device. md5sum of /dev/zero because toybox has it, it never blocks on I/O, and it keeps a core
# genuinely busy rather than spinning in the shell interpreter.
i=0
while [ $i -lt 8 ]; do
  md5sum /dev/zero >/dev/null 2>&1 &
  i=$((i + 1))
done
wait
