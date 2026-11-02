#!/usr/bin/env bash
# Runs a test Navidrome on 127.0.0.1:4533 with the made-up library, set up the
# way the car's server is: user "me" / "secret", and the car's player
# ("CarMediaPlayer") transcoding to Opus 128k.  Navidrome's transcoding cache
# starts empty each time, so songs stream as they're transcoded at first.
# Stays in the foreground; stop it with Ctrl-C.  Usage: start.sh <work dir>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dir="$(cd "${1:?usage: start.sh <work dir>}" && pwd)"
port=4533
url="http://127.0.0.1:$port"
[ -x "$dir/navidrome" ] || { echo "no $dir/navidrome: run build.sh first"; exit 1; }
mkdir -p "$dir/data" "$dir/music"
[ -n "$(ls -A "$dir/music")" ] || "$here/make-library.sh" "$dir/music"
rm -rf "$dir/data/cache/transcoding"

ND_MUSICFOLDER="$dir/music" ND_DATAFOLDER="$dir/data" ND_ADDRESS=127.0.0.1 ND_PORT=$port \
ND_LOGLEVEL="${ND_LOGLEVEL:-info}" ND_ENABLEINSIGHTSCOLLECTOR=false ND_ENABLEEXTERNALSERVICES=false \
  "$dir/navidrome" > "$dir/server.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT
for _ in $(seq 1 60); do curl -s -o /dev/null "$url/ping" && break; sleep 0.5; done

# First run: the admin user.
curl -s -X POST -H 'Content-Type: application/json' -d '{"username":"me","password":"secret"}' \
  "$url/auth/createAdmin" > /dev/null || true
# The car's player is "CarMediaPlayer" plus the HTTP client's name: the car's
# (Dalvik) and the tests' (Java).  Make both, then set them to Opus 128k.
for agent in "Java/21" "Dalvik/2.1.0 (Linux; U; Android 9)"; do
  curl -s -o /dev/null -A "$agent" "$url/rest/ping?u=me&p=secret&v=1.16.1&c=CarMediaPlayer&f=json"
done
token=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"username":"me","password":"secret"}' "$url/auth/login" |
  python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
auth="x-nd-authorization: Bearer $token"
opus=$(curl -s -H "$auth" "$url/api/transcoding" |
  python3 -c 'import sys,json; print(next(t["id"] for t in json.load(sys.stdin) if t["targetFormat"]=="opus"))')
for id in $(curl -s -H "$auth" "$url/api/player" |
            python3 -c 'import sys,json; [print(p["id"]) for p in json.load(sys.stdin) if p["client"]=="CarMediaPlayer"]'); do
  curl -s -H "$auth" "$url/api/player/$id" |
    python3 -c "import sys,json; p=json.load(sys.stdin); p['transcodingId']='$opus'; p['maxBitRate']=128; print(json.dumps(p))" |
    curl -s -o /dev/null -X PUT -H "$auth" -H 'Content-Type: application/json' --data @- "$url/api/player/$id"
done
echo "Navidrome running at $url (log: $dir/server.log)"
wait $server
