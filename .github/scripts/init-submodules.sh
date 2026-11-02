#!/usr/bin/env bash
# Fetch every submodule at its pinned commit with depth 1 (GitHub allows fetching by SHA).
set -u
cd "$1"
init_tree() {
  local root="$1"
  [ -f "$root/.gitmodules" ] || return 0
  git -C "$root" config --file .gitmodules --get-regexp path | awk '{print $2}' | while read -r path; do
    local sha
    sha=$(git -C "$root" ls-tree HEAD "$path" | awk '{print $3}')
    [ -n "$sha" ] || { echo "no gitlink for $path"; continue; }
    if git -C "$root/$path" rev-parse --verify -q "$sha^{commit}" >/dev/null 2>&1 && [ "$(git -C "$root/$path" rev-parse HEAD 2>/dev/null)" = "$sha" ]; then
      echo "ok      $path"
    else
      git -C "$root" submodule init -- "$path" >/dev/null 2>&1
      git -C "$root" submodule update --depth 1 -- "$path" >/dev/null 2>&1
      if [ ! -d "$root/$path/.git" ] && [ ! -f "$root/$path/.git" ]; then
        url=$(git -C "$root" config --file .gitmodules --get "submodule.$path.url")
        git clone -q --no-checkout --depth 1 "$url" "$root/$path" 2>/dev/null || true
      fi
      git -C "$root/$path" fetch -q --depth 1 origin "$sha" 2>&1 | tail -1
      if git -C "$root/$path" checkout -q -f "$sha" 2>/dev/null; then echo "fetched $path"; else echo "FAILED  $path ($sha)"; fi
    fi
    init_tree "$root/$path"
  done
}
init_tree "$(pwd)"
echo DONE
