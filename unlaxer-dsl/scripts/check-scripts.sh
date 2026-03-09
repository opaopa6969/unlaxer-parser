#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPTS_DIR="$ROOT_DIR/scripts"

mapfile -t files < <(find "$SCRIPTS_DIR" -type f -name '*.sh' | sort)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "[check-scripts] No scripts found under scripts/."
  exit 0
fi

for file in "${files[@]}"; do
  first_line="$(head -n1 "$file" || true)"
  if [[ "$first_line" != "#!/usr/bin/env bash" ]]; then
    echo "[check-scripts] ERROR: invalid shebang in $file" >&2
    echo "  expected: #!/usr/bin/env bash" >&2
    echo "  actual:   $first_line" >&2
    echo "[check-scripts] Fix: update the first line to '#!/usr/bin/env bash'." >&2
    exit 1
  fi

  if ! bash -n "$file"; then
    echo "[check-scripts] ERROR: syntax check failed for $file" >&2
    echo "[check-scripts] Fix: run 'bash -n $file' locally and correct reported syntax errors." >&2
    exit 1
  fi

  if command -v shellcheck >/dev/null 2>&1; then
    if ! shellcheck "$file"; then
      echo "[check-scripts] ERROR: shellcheck failed for $file" >&2
      echo "[check-scripts] Fix: apply the shellcheck suggestions for $file." >&2
      exit 1
    fi
  fi

done

if command -v shellcheck >/dev/null 2>&1; then
  echo "[check-scripts] OK: ${#files[@]} script(s) passed shebang, syntax, and shellcheck."
else
  echo "[check-scripts] OK: ${#files[@]} script(s) passed shebang and syntax checks (shellcheck not installed)."
fi
