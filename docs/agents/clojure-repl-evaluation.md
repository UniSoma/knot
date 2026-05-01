# Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Default port: 7888.** The dev container's `.aishell/config.yaml` runs `bb nrepl-server 7888` on entry, so the REPL is normally already up — use it directly without discovery.

**Evaluate code:**

`clj-nrepl-eval -p 7888 "<clojure-code>"`

With timeout (milliseconds):

`clj-nrepl-eval -p 7888 --timeout 5000 "<clojure-code>"`

## Fallback when 7888 is not reachable

If you get connection refused on 7888, start a REPL yourself, then retry:

```bash
bb nrepl-server 7888 &
clj-nrepl-eval -p 7888 "<clojure-code>"
```

If you can't bind to 7888 (already in use by something else), discover whatever port is live:

`clj-nrepl-eval --discover-ports`

## REPL session

The session persists between evaluations — namespaces and state are maintained. Always use `:reload` when requiring namespaces to pick up changes.
