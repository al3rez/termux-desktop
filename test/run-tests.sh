#!/usr/bin/env bash
# Headless render regression tests. Run from the repo root:
#   javac -cp "lib/*" -d out $(find src -name '*.java')
#   test/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

javac -cp out -d "$TMP" test/RenderHarness.java test/RenderChecks.java test/EmojiRenderChecks.java
DEJAVU=/usr/share/fonts/TTF/DejaVuSansMono.ttf

# 1. Powerline separators must span the full cell height and keep segments joined.
python3 -c "
import sys
e='\x1b'
sys.stdout.write(f'{e}[41;97m A {e}[0;31;44m{e}[97m B {e}[0;34m{e}[0m')
" | java -cp "out:lib/*:$TMP" RenderHarness "$TMP/pl.png" "$DEJAVU" 20

# 2. Nerd-font icons must not be squeezed: the branch icon's inked width
#    must be at least half its cell (a double-width fallback font scaled
#    into one cell fails this).
# DejaVu has no nerd-font PUA glyphs, forcing the fallback chain to be used
# (the logical Monospaced font satisfies them itself via fontconfig).
python3 -c "import sys; sys.stdout.write('\uE0A0 branch')" \
  | java -cp "out:lib/*:$TMP" RenderHarness "$TMP/icons.png" "$DEJAVU" 20

# 3. Emoji clusters: emoji are centered in two visual cells, VS16/flags do
#    not drift following text, and a color path is checked when the runtime supports it.
python3 -c "import sys; sys.stdout.write('A ' + chr(0x1F600) + ' B ' + chr(0x2764) + chr(0xFE0F) + ' C ' + chr(0x1F1F9) + chr(0x1F1ED) + ' D'); sys.stdout.write(chr(10))" \
  | java -cp "out:lib/*:$TMP" RenderHarness "$TMP/emoji.png" "$DEJAVU" 20

CELL=$(java -cp "out:lib/*:$TMP" RenderHarness "$TMP/cell.png" "$DEJAVU" 20 </dev/null | sed 's/.*cell=//')
CW=${CELL%x*}; CH=${CELL#*x}
java -cp "out:lib/*:$TMP" RenderChecks "$TMP/pl.png" "$TMP/icons.png" "$CW" "$CH"
java -cp "out:lib/*:$TMP" EmojiRenderChecks "$TMP/emoji.png" "$CW" "$CH" "$DEJAVU" 20
echo "ALL RENDER TESTS PASSED"
