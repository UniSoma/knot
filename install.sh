#!/bin/sh
# Install the knot release binary for this machine.
#
#   curl -fsSL https://raw.githubusercontent.com/UniSoma/knot/main/install.sh | sh
#
# KNOT_VERSION      release to install, e.g. 0.14.0 (default: latest)
# KNOT_INSTALL_DIR  where to put knot (default: ~/.local/bin)
# KNOT_RELEASE_URL  releases base URL (default: https://github.com/UniSoma/knot/releases)

set -eu

install_knot() {
  release_url="${KNOT_RELEASE_URL:-https://github.com/UniSoma/knot/releases}"
  install_dir="${KNOT_INSTALL_DIR:-$HOME/.local/bin}"

  case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=macos ;;
    *) echo "knot: unsupported OS $(uname -s); on Windows use install.ps1" >&2; exit 1 ;;
  esac

  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) echo "knot: unsupported architecture $(uname -m)" >&2; exit 1 ;;
  esac

  # A shell running under Rosetta reports x86_64 on Apple silicon.
  if [ "$os" = macos ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then
    arch=aarch64
  fi

  asset="knot-$os-$arch.tar.gz"
  if [ -n "${KNOT_VERSION:-}" ]; then
    base="$release_url/download/v${KNOT_VERSION#v}"
  else
    base="$release_url/latest/download"
  fi

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  echo "Downloading $base/$asset"
  curl -fsSL -o "$tmp/$asset" "$base/$asset"
  curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS"

  expected="$(awk -v f="$asset" '$2 == f { print $1 }' "$tmp/SHA256SUMS")"
  if [ -z "$expected" ]; then
    echo "knot: $asset is not listed in SHA256SUMS" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$tmp/$asset" | awk '{ print $1 }')"
  else
    actual="$(shasum -a 256 "$tmp/$asset" | awk '{ print $1 }')"
  fi
  if [ "$expected" != "$actual" ]; then
    echo "knot: checksum mismatch for $asset (expected $expected, got $actual)" >&2
    exit 1
  fi

  tar -xzf "$tmp/$asset" -C "$tmp" knot
  mkdir -p "$install_dir"
  mv "$tmp/knot" "$install_dir/knot"
  chmod +x "$install_dir/knot"
  echo "Installed knot $("$install_dir/knot" --version) to $install_dir/knot"

  case ":$PATH:" in
    *":$install_dir:"*) ;;
    *) echo "knot: warning: $install_dir is not on PATH" >&2 ;;
  esac
}

install_knot
