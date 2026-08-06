#!/system/bin/sh
# samples what the device is doing to itself, once a second, until it is killed.
#
# it exists because the Odin 3 powered off four times while the game was being brought up, and
# "thermal" was a guess with
# nothing behind it. a reboot takes the answer with it, so this writes and *syncs* as it goes:
# whatever the last line says is what the device was at when it stopped existing.
#
# usage: monitor.sh <logfile>
LOG="$1"
: >"$LOG"
START=$(date +%s)
N=0
while true; do
  NOW=$(date +%s)
  EL=$((NOW - START))

  # the hottest thermal zone, and which one. the SoC has dozens; the maximum is the one that
  # trips a shutdown, and its name says whether it is the CPU, the GPU, the battery or the skin.
  HOT=0
  HOTNAME=none
  for Z in /sys/class/thermal/thermal_zone*; do
    T=$(cat "$Z/temp" 2>/dev/null)
    case "$T" in ''|*[!0-9-]*) continue;; esac
    if [ "$T" -gt "$HOT" ]; then
      HOT=$T
      HOTNAME=$(cat "$Z/type" 2>/dev/null)
    fi
  done

  # the power side, which is the other way a handheld dies under load: a rail sagging is not a
  # temperature and would never show up above.
  BATT=/sys/class/power_supply/battery
  VOLT=$(cat $BATT/voltage_now 2>/dev/null)
  CURR=$(cat $BATT/current_now 2>/dev/null)
  BTEMP=$(cat $BATT/temp 2>/dev/null)
  CAP=$(cat $BATT/capacity 2>/dev/null)

  # and whether the kernel is clocking the cores down, which is what thermal mitigation looks
  # like before it gives up.
  F0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)
  F7=$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null)

  echo "t=${EL} hot=${HOT} zone=${HOTNAME} volt=${VOLT} curr=${CURR} btemp=${BTEMP} cap=${CAP} cpu0=${F0} cpu7=${F7}" >>"$LOG"

  N=$((N + 1))
  if [ $((N % 5)) -eq 0 ]; then sync; fi
  sleep 1
done
