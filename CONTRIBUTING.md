# Contributing

Work is trunk-based: branch from an up-to-date `master` (`feature/<slug>`, `fix/<slug>` or
`chore/<slug>`), open a pull request, keep CI (`./gradlew clean build`) green, squash merge.
Commit messages follow the conventional style (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`).

All engineering conventions, architectural boundaries and verification gates live in
[AGENTS.md](AGENTS.md) — it is written for AI coding agents and humans alike; read it first.

Runtime prerequisites (Ollama, ONNX model files) and the API overview are in [README.md](README.md).
The design plans (`docs/*-plan.md`) and checkpoint evidence are maintained locally by the maintainer
and are intentionally not part of this repository; `docs/observability.md` and `docs/eval-log.md`
are the published documents. If you need design context that is missing, open an issue.
