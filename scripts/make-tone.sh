#!/bin/sh
# Writes the synthetic test clip: 0.5 s of a 440 Hz sine, mono Ogg Opus. No voice, no recording, nothing private.
# Needs ffmpeg with libopus. The output is committed (about 4 KB); run this only to change it.
set -eu
out="$(dirname "$0")/../app/src/test/resources/tone.ogg"
ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=440:duration=0.5:sample_rate=48000" \
  -ac 1 -c:a libopus -b:a 16k -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$out"
ls -l "$out"
