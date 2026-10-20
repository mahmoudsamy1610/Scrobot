#!/usr/bin/env bash
# Proves the lambda desugaring is faithful: runs tools/desugar/src/DesugarCheck.kt twice, once
# against the original kotlin-stdlib and once against the desugared one, and compares the output.
# It also checks that nothing that will be dexed still carries an invokedynamic call site.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/scrobot-desugar-check"
GRADLE="${GRADLE:-$ROOT/gradlew}"
DESUGAR="$ROOT/tools/desugar/build/install/scrobot-desugar/bin/scrobot-desugar"

rm -rf "$WORK"
mkdir -p "$WORK"

[ -x "$DESUGAR" ] || "$GRADLE" -q --no-daemon -p "$ROOT/tools/desugar" installDist
CHECK_JAR=$(find "$ROOT/tools/desugar/build/install/scrobot-desugar/lib" -name 'scrobot-desugar*.jar' | head -1)
STDLIB=$(find "$ROOT/tools/desugar/build/install/scrobot-desugar/lib" -name 'kotlin-stdlib-*.jar' | head -1)
[ -n "$STDLIB" ] || { echo "check-desugar: no kotlin-stdlib found" >&2; exit 1; }

echo "== desugaring $(basename "$STDLIB")"
"$DESUGAR" "$STDLIB" "$WORK/stdlib-desugared.jar"

echo "== running the checks against the original library"
java -cp "$CHECK_JAR:$STDLIB" com.mahmoud.scrobot.desugar.DesugarCheckKt > "$WORK/before.txt"

echo "== running the same checks against the desugared library"
java -cp "$CHECK_JAR:$WORK/stdlib-desugared.jar" com.mahmoud.scrobot.desugar.DesugarCheckKt > "$WORK/after.txt"

if ! diff -u "$WORK/before.txt" "$WORK/after.txt" > "$WORK/diff.txt"; then
  echo "FAIL: the desugared library behaves differently:" >&2
  head -40 "$WORK/diff.txt" >&2
  exit 1
fi
echo "PASS  $(wc -l < "$WORK/after.txt") stdlib results identical before and after desugaring"

# Nothing that gets dexed may still contain a lambda call site. Java 9+ copies inside a
# multi-release jar are dropped before dexing, so they are not counted.
left=0
for jar in "$@"; do
  dir="$WORK/scan/$(basename "$jar")"
  mkdir -p "$dir"
  (cd "$dir" && unzip -qo "$jar")
  while IFS= read -r class; do
    case "$class" in */META-INF/versions/*) continue ;; esac
    if grep -qa BootstrapMethods "$class"; then
      echo "FAIL: $(basename "$jar") still has invokedynamic in ${class#"$dir"/}" >&2
      left=$((left + 1))
    fi
  done < <(find "$dir" -name '*.class')
done
[ "$left" -eq 0 ] || exit 1
[ $# -eq 0 ] || echo "PASS  $# jars carry no invokedynamic"
