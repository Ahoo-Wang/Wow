#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="${1:-.}"

exec python3 "$script_dir/verify-local-migration.py" "$root"
