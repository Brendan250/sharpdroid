#!/system/bin/sh
# runs one soak with the monitor attached, and leaves both logs behind.
#
# usage: soak.sh <label> <seconds> <command...>
cd /data/local/tmp/sharpemu || exit 1
export LD_LIBRARY_PATH=.
LABEL="$1"; shift
SECS="$1"; shift

MON="/data/local/tmp/sharpemu/soak-${LABEL}-monitor.log"
OUT="/data/local/tmp/sharpemu/soak-${LABEL}-run.log"

sh /data/local/tmp/sharpemu/monitor.sh "$MON" &
MONPID=$!

echo "[soak] ${LABEL}: ${SECS}s of: $*" | tee "$OUT"
sync
timeout "$SECS" "$@" >>"$OUT" 2>&1
echo "[soak] ${LABEL}: command exited $?" >>"$OUT"

kill $MONPID 2>/dev/null
sync
echo "[soak] ${LABEL} done"
tail -n 1 "$MON"
