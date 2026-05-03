# CLAUDE.md

Guidance for AI assistants (e.g. Claude Code) working in this repository.

## Repository status

This repository is in a pre-implementation state. As of the latest commit it
contains only a `.gitkeep` placeholder and a single `Initialize repository`
commit. No source code, build configuration, tests, or tooling exist yet.

The repository name (`vllm4android`) suggests the goal is to bring
[vLLM](https://github.com/vllm-project/vllm) — a high-throughput LLM inference
and serving engine — to the Android platform. Treat this as the working
hypothesis until a `README.md`, design doc, or initial scaffolding lands.

When code is added, **update this file in the same change** so it reflects
reality rather than this placeholder description.

## Repository layout

```
.
├── .gitkeep        # placeholder, remove once real files exist
└── CLAUDE.md       # this file
```

## Development workflow

### Branching

- `main` is the integration branch.
- Feature work happens on prefixed branches. Claude-authored work uses
  `claude/<short-description>-<suffix>` (e.g.
  `claude/add-claude-documentation-6d9kS`).
- Do **not** push directly to `main`. Always push to the assigned feature
  branch and open a PR only when explicitly asked.

### Commits

- Write descriptive messages focused on the *why*.
- Prefer small, logical commits over a single squashed change.
- Never use `--no-verify` or skip hooks unless the user explicitly requests it.
- Never amend a commit that has already been pushed.

### Pushing

- Use `git push -u origin <branch-name>`.
- On transient network failures, retry up to 4 times with exponential backoff
  (2s, 4s, 8s, 16s). Do not retry on non-network errors — diagnose instead.

### Pull requests

- Do **not** open a PR unless the user asks for one.
- This repo is restricted to the `becxer/vllm4android` remote. Do not interact
  with other repositories via the GitHub MCP tools.

## Conventions to establish

The following are not yet decided. When the first real change introduces a
choice, record it here:

- **Language / runtime** — likely a mix of Kotlin/Java (Android app layer),
  C++ (inference kernels, NDK), and possibly Python (tooling, model
  conversion). Confirm before assuming.
- **Build system** — Gradle for the Android side; CMake/Ninja for native
  components is conventional. Not yet present.
- **Target Android API level / NDK version** — to be decided.
- **Model format / runtime backend** — vLLM upstream targets CUDA; Android
  deployment will need a different backend (e.g. GGUF + llama.cpp,
  ONNX Runtime Mobile, MediaPipe LLM, MLC-LLM, or a custom NDK port).
  Document the choice when made.
- **Testing strategy** — unit tests, instrumented tests, on-device
  benchmarks. None set up yet.
- **Linting / formatting** — `ktlint`/`detekt`, `clang-format`, etc.
  Not yet configured.

## Guidance for AI assistants

- **Do not invent project structure.** With nothing to go on, ask the user
  before scaffolding an Android project, picking a backend, or adding
  dependencies. Those are architectural decisions, not implementation
  details.
- **Read before writing.** Once files exist, use `Read` / `Grep` to
  understand current patterns before editing. Match the surrounding style.
- **Prefer editing existing files** over creating new ones. Do not create
  README.md, docs, or example files unless asked.
- **No speculative abstractions.** Build only what the current task
  requires.
- **Keep this file current.** When you add the first build file, source
  tree, or CI workflow, replace the corresponding section above with the
  concrete details (commands, paths, conventions actually in use).

## Useful commands

None yet — there is nothing to build, run, or test. Populate this section
as soon as a build system is in place. Expected entries (placeholder):

```
# Build the Android app (once Gradle is set up)
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests on a connected device
./gradlew connectedAndroidTest

# Build native components (once CMake is wired in)
cmake --build build
```
