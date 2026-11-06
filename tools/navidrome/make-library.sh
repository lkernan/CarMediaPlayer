#!/usr/bin/env bash
# Makes a small library of made-up music for a test Navidrome: invented
# artists and albums, tagged, with covers, in FLAC and MP3, plus one long song
# (longer than the player's two-minute read-ahead).  Usage: make-library.sh <dir>
set -euo pipefail
out="${1:?usage: make-library.sh <music dir>}"
mkdir -p "$out"

# song <file> <seconds> <frequency> <title> <artist> <album> <track> <year> <genre>
song() {
  local file=$1 secs=$2 freq=$3 title=$4 artist=$5 album=$6 track=$7 year=$8 genre=$9
  [ -f "$file" ] && return
  ffmpeg -hide_banner -v error -y \
    -f lavfi -i "sine=frequency=$freq:duration=$secs" \
    -f lavfi -i "sine=frequency=$((freq * 3 / 2)):duration=$secs" \
    -f lavfi -i "anoisesrc=d=$secs:a=0.03" \
    -filter_complex "[0][1][2]amix=inputs=3,aformat=channel_layouts=stereo,volume=2" -ar 44100 \
    -metadata title="$title" -metadata artist="$artist" -metadata album_artist="$artist" \
    -metadata album="$album" -metadata track="$track" -metadata date="$year" -metadata genre="$genre" \
    "$file"
}

cover() {
  [ -f "$1/cover.jpg" ] && return
  ffmpeg -hide_banner -v error -y -f lavfi -i "gradients=s=600x600:c0=$2:c1=$3:seed=1" -frames:v 1 "$1/cover.jpg"
}

d="$out/The Paper Lanterns/Harbour Lights"; mkdir -p "$d"; cover "$d" 0x1d4e89 0xf7b267
for i in 1 2 3 4 5; do
  t=("" "Low Water" "Signal Fires" "The Long Jetty" "Salt and Static" "Harbour Lights")
  song "$d/0$i - ${t[$i]}.flac" $((40 + i * 7)) $((220 + i * 30)) "${t[$i]}" "The Paper Lanterns" "Harbour Lights" $i 2019 Indie
done

d="$out/Mira Solano/Glass Orchard"; mkdir -p "$d"; cover "$d" 0x2a9d8f 0xe9c46a
for i in 1 2 3 4; do
  t=("" "Glass Orchard" "Paper Moons" "Second Summer" "Quiet Machines")
  song "$d/0$i - ${t[$i]}.flac" $((45 + i * 9)) $((300 + i * 25)) "${t[$i]}" "Mira Solano" "Glass Orchard" $i 2021 Pop
done

d="$out/Northbound Static/Low Tide Radio"; mkdir -p "$d"; cover "$d" 0x6d597a 0xb56576
for i in 1 2 3 4; do
  t=("" "Low Tide Radio" "Coastline Hum" "Night Ferry" "Long Way Round")
  secs=$((50 + i * 6)); [ $i -eq 4 ] && secs=320
  f="$d/0$i - ${t[$i]}.mp3"
  if [ ! -f "$f" ]; then
    song "$d/tmp$i.flac" $secs $((180 + i * 40)) "${t[$i]}" "Northbound Static" "Low Tide Radio" $i 2023 Electronic
    ffmpeg -hide_banner -v error -y -i "$d/tmp$i.flac" -map_metadata 0 -c:a libmp3lame -b:a 192k "$f"
    rm "$d/tmp$i.flac"
  fi
done
echo "library in $out: $(find "$out" -name '*.flac' -o -name '*.mp3' | wc -l) songs"
