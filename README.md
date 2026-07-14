# termux-desktop

Termux's terminal engine running as a Linux desktop terminal.

The `com.termux.terminal` classes are copied unmodified from
[termux/termux-app](https://github.com/termux/termux-app) (terminal-emulator
module). Around them:

- `Java2DRenderer` — a line-for-line port of Termux's `TerminalRenderer` from
  android.graphics to Java2D, keeping its signature behavior: style runs are
  drawn as whole strings (so proportional fonts shape naturally) and runs whose
  measured width disagrees with wcwidth are horizontally scaled into the cell
  grid. Adds per-code-point font fallback (`Font.canDisplay`), which Android
  gets from the system for free.
- Desktop `TerminalSession` on [pty4j](https://github.com/JetBrains/pty4j)
  instead of Android JNI.
- Small `android.*` shims so the Termux sources compile untouched.
- Swing shell with truecolor, key handling via Termux's `KeyHandler`.

## Build & run

```sh
lib/fetch-deps.sh
javac -cp "lib/*" -d out $(find src -name '*.java')
java -Dsun.java2d.opengl=true -cp "out:lib/*" com.termux.desktop.TermuxDesktop [font.ttf] [size]
```

Ctrl+Alt+= / Ctrl+Alt+- zoom, Ctrl+Shift+V paste.

## Tests

Run the JUnit 5 pipeline, performance, and daemon supervision tests:

```sh
gradle --console=plain -q test --rerun-tasks
```

Run those tests together with the headless rendering regressions:

```sh
javac -cp "lib/*" -d out $(find src -name '*.java')
test/run-tests.sh
```

## License

GPLv3, inherited from termux-app.
