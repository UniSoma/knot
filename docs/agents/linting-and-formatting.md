# Linting & Formatting

## Before commit

```bash
clj-kondo --lint src test
```

Project-wide lint config lives at `.clj-kondo/config.edn`.

## Parenthesis repair

Do **not** manually repair parenthesis errors. Run:

```bash
clj-paren-repair <files>
```

The tool auto-formats with cljfmt. It ships in the dev container — see `.aishell/Dockerfile` if you need to reinstall it elsewhere.
