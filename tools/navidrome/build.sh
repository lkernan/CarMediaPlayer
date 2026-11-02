#!/usr/bin/env bash
# Builds a Navidrome server from source (API only: the web UI is left out).
# Usage: build.sh <work dir> [version]   →  <work dir>/navidrome
# Needs git, Go (it fetches the toolchain the source asks for) and gcc.
set -euo pipefail
dir="${1:?usage: build.sh <work dir> [version]}"
version="${2:-v0.64.2}"
mkdir -p "$dir"
[ -d "$dir/src" ] || git clone -q --depth 1 --branch "$version" https://github.com/navidrome/navidrome.git "$dir/src"
cd "$dir/src"
echo '<html><body>no UI</body></html>' > ui/build/index.html
GOTOOLCHAIN=auto GOFLAGS=-mod=mod go build -tags=netgo,sqlite_fts5 \
  -ldflags="-X github.com/navidrome/navidrome/consts.gitTag=$version" -o "$dir/navidrome" .
echo "built $dir/navidrome"
