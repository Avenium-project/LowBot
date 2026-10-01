#!/usr/bin/env bash
# Fetches the Linux runtime for the bots' terminal (needs network; run by CI or by hand):
#   - proot, its loader and libtalloc from the Termux package repository (built for Android),
#     stored as lib*.so in mobile/jniLibs/<abi>/ so Android installs them as executables
#     (apps targeting API 29+ may not execute files they download themselves);
#   - the current Alpine Linux minirootfs pinned by URL + SHA-256 (mobile/linux-runtime/alpine.json),
#     which the phone downloads on first use and verifies against that hash.
# Each .deb is checked against the SHA-256 in the repository index. Licences: proot GPL-2.0,
# talloc LGPL-3.0 (sources: github.com/termux/proot, talloc.samba.org).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JNI="$HERE/../jniLibs"
REPO="https://packages.termux.dev/apt/termux-main"
ALPINE="https://dl-cdn.alpinelinux.org/alpine"
WORK="$(mktemp -d)"
LOCK="$HERE/runtime.lock"
: > "$LOCK.tmp"

field() { # $1=Packages $2=package $3=field
  awk -v p="$2" -v f="$3" 'BEGIN{RS="";FS="\n"} { n=""; v=""; for(i=1;i<=NF;i++){ if($i ~ /^Package: /) n=substr($i,10); if(index($i, f": ")==1) v=substr($i,length(f)+3) } if(n==p){print v; exit} }' "$1"
}

fetch_deb() { # $1=arch $2=package
  local idx="$WORK/$1/Packages" file sha ver
  file="$(field "$idx" "$2" Filename)"; sha="$(field "$idx" "$2" SHA256)"; ver="$(field "$idx" "$2" Version)"
  [ -n "$file" ] && [ -n "$sha" ] || { echo "package $2 not found for $1"; exit 1; }
  curl -fsSL "$REPO/$file" -o "$WORK/$1/$2.deb"
  echo "$sha  $WORK/$1/$2.deb" | sha256sum -c - >/dev/null
  dpkg-deb -x "$WORK/$1/$2.deb" "$WORK/$1/root"
  echo "termux $1 $2 $ver sha256=$sha" >> "$LOCK.tmp"
}

for pair in "aarch64:arm64-v8a" "x86_64:x86_64"; do
  arch="${pair%%:*}"; abi="${pair##*:}"
  rm -rf "$JNI/$abi"; mkdir -p "$WORK/$arch/root" "$JNI/$abi"
  curl -fsSL "$REPO/dists/stable/main/binary-$arch/Packages" -o "$WORK/$arch/Packages"
  fetch_deb "$arch" proot
  fetch_deb "$arch" libtalloc
  P="$WORK/$arch/root/data/data/com.termux/files/usr"
  cp "$P/bin/proot" "$JNI/$abi/libproot.so"
  cp "$P/libexec/proot/loader" "$JNI/$abi/libproot-loader.so"
  if [ -f "$P/libexec/proot/loader32" ]; then cp "$P/libexec/proot/loader32" "$JNI/$abi/libproot-loader32.so"; fi
  cp -L "$P/lib/libtalloc.so.2" "$JNI/$abi/libtalloc.so"
  # Android only installs files named lib*.so: point proot at libtalloc.so and drop Termux's rpath.
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$JNI/$abi/libproot.so"
  patchelf --remove-rpath "$JNI/$abi/libproot.so" || true
  patchelf --set-soname libtalloc.so "$JNI/$abi/libtalloc.so" || true
  patchelf --remove-rpath "$JNI/$abi/libtalloc.so" || true
  for f in "$JNI/$abi"/*.so; do
    needed="$(readelf -d "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
    echo "$abi $(basename "$f"): ${needed:-static}"
    for n in $needed; do
      case "$n" in libc.so|libdl.so|libm.so|liblog.so|libtalloc.so) ;; *) echo "unexpected dependency $n in $f"; exit 1;; esac
    done
  done
done

python3 - "$ALPINE" "$HERE/alpine.json" <<'PY'
import json, sys, urllib.request
base, out = sys.argv[1], sys.argv[2]
res = {}
for arch in ("aarch64", "x86_64"):
    text = urllib.request.urlopen(f"{base}/latest-stable/releases/{arch}/latest-releases.yaml").read().decode()
    items, cur = [], None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("-"):
            if cur is not None: items.append(cur)
            cur = {}; line = line[1:].strip()
        if cur is not None and ":" in line:
            k, v = line.split(":", 1); cur[k.strip()] = v.strip().strip('"')
    if cur is not None: items.append(cur)
    mini = next(i for i in items if i.get("flavor") == "alpine-minirootfs")
    branch = "v" + ".".join(mini["version"].split(".")[:2])
    res[arch] = {"version": mini["version"], "url": f"{base}/{branch}/releases/{arch}/{mini['file']}",
                 "sha256": mini["sha256"], "size": int(mini.get("size", 0) or 0)}
json.dump(res, open(out, "w"), indent=2)
print(json.dumps(res, indent=2))
PY
mv "$LOCK.tmp" "$LOCK"
cat "$LOCK"
