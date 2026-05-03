# CLAUDE.md

Guidance for AI assistants (e.g. Claude Code) working in this repository.

## Project goal

vLLM-style on-device LLM server for Android. Loads **Gemma 4 E2B IT**
(`litert-community/gemma-4-E2B-it-litert-lm`) via [LiteRT-LM][litertlm] and
exposes an **OpenAI Chat Completions–compatible** HTTP API
(`POST /v1/chat/completions`, SSE streaming when `stream=true`) on the
device, so existing OpenAI SDK clients can target the phone as their backend.

We are *not* implementing PagedAttention / continuous batching — those make
no sense on a single-user mobile device. The "vLLM-like" parts we do keep:
the OpenAI REST surface, streaming via SSE, model warm-loading at startup.

[litertlm]: https://github.com/google-ai-edge/LiteRT-LM

## Module layout

```
.
├── app/        # Android app: Compose UI, foreground service, lifecycle glue
├── engine/     # LiteRT-LM wrapper: model download, Engine/Conversation, chat template
├── server/     # Ktor (CIO) HTTP server: OpenAI-compatible routes + DTOs
├── gradle/libs.versions.toml   # version catalog
├── settings.gradle.kts
├── build.gradle.kts
└── gradle.properties
```

Module dependencies: `app → server → engine`. `engine` is the only module
that links to `com.google.ai.edge.litertlm:litertlm-android`.

### `engine/`

- `LlmEngine.kt` — owns a single LiteRT-LM `Engine`, serializes generation
  with a `Mutex` (one phone, one in-flight request), exposes a cold
  `Flow<String>` of streamed chunks via `generateStream()`.
- `ChatTemplate.kt` — renders OpenAI-style `messages: [...]` into Gemma's
  `<start_of_turn>user … <end_of_turn>` format. System messages fold into
  the first user turn (Gemma has no dedicated system role).
- `ModelDownloader.kt` — first-launch download from Hugging Face
  (`https://huggingface.co/{repo}/resolve/{rev}/{file}`), atomic rename via
  `.part`, cached under `filesDir`.

### `server/`

- `dto/OpenAiDto.kt` — `@Serializable` request/response/chunk types.
- `OpenAiServer.kt` — Ktor `embeddedServer(CIO, ...)` with:
  - `GET /healthz`
  - `GET /v1/models`
  - `POST /v1/chat/completions` (non-stream → single `chat.completion`,
    stream → SSE `data: {...chunk...}\n\n` ending in `data: [DONE]`).

### `app/`

- `MainActivity.kt` — Compose UI (start/stop, progress, sample curl).
- `service/LlmServerService.kt` — `Service` (foreground, `dataSync` type)
  that runs the download → load → serve pipeline and exposes a `StateFlow`
  to the activity via a local binder.

## Conventions / decisions

These were chosen during initial scaffolding. Revisit explicitly if you want
to change — don't drift.

- **minSdk 31, targetSdk 34, JDK 17, Kotlin 2.1, AGP 8.7.**
- **Kotlin DSL** for all Gradle files. Versions live in
  `gradle/libs.versions.toml` (version catalog) — never hard-code a version
  inside a `build.gradle.kts`.
- **HTTP server: Ktor + CIO engine.** Picked over NanoHTTPD because Ktor
  has first-class coroutines/Flow integration and we already stream tokens
  as a Flow.
- **Server lifecycle: foreground `Service`** with `foregroundServiceType="dataSync"`.
  Required on Android 12+ for long-running networking. The service owns the
  `LlmEngine` instance — there is exactly one per process.
- **Concurrency model:** one `Engine`, one in-flight generation. We
  serialize requests with a `Mutex` inside `LlmEngine` rather than queueing
  at the HTTP layer.
- **Model distribution:** download from HF on first run into `filesDir`.
  Do **not** check `.litertlm` files into git (already in `.gitignore`).
- **Backend:** `Backend.CPU()` (XNNPACK, 4 threads) by default. GPU/NPU
  variants exist on the model repo — switching requires loading a different
  `.litertlm` artifact, not just changing the `Backend` enum.
- **Chat template:** Gemma format. If we add non-Gemma models later, route
  on `req.model` rather than branching inside `ChatTemplate.renderGemma`.

## Adding a new endpoint

1. Add the request/response types to `server/dto/OpenAiDto.kt` with
   `@Serializable` and `@SerialName` for any snake_case fields.
2. Add the route inside the `routing { }` block in `OpenAiServer.kt`.
3. If it generates text, go through `LlmEngine.generateStream()` so the
   mutex/serialization stays in one place.

## Adding a new model

1. Add a `ModelDownloader.Spec` constant alongside `GEMMA_4_E2B_IT`.
2. If the prompt format differs from Gemma, add a renderer to
   `ChatTemplate` and dispatch on `req.model` in the chat completions route.
3. Update the `/v1/models` listing in `OpenAiServer.kt`.

## Useful commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Install to a connected device
./gradlew :app:installDebug

# Type-check / lint everything
./gradlew check

# Once the server is running on the device, port-forward and hit it:
adb forward tcp:8080 tcp:8080
curl http://localhost:8080/v1/models
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma-4-E2B-it","messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) is **not yet** committed —
generate it once with `gradle wrapper --gradle-version 8.11.1` on a machine
that has Gradle installed, then commit the result.

## Workflow

- Branch: `claude/add-claude-documentation-6d9kS` (current). Don't push to
  `main`. PRs only when the user asks.
- `git push -u origin <branch>`. Retry network failures up to 4× with
  exponential backoff (2/4/8/16s). Don't retry non-network failures.
- This repo is restricted to `becxer/vllm4android` for any GitHub MCP calls.

## Guidance for AI assistants

- **Read before writing.** Match existing module/package conventions
  (`com.vllm4android.{app,engine,server}`).
- **No premature abstractions.** A second model arriving is the time to
  introduce a `ModelRegistry`, not before.
- **No new docs unless asked.** Keep this file in sync; don't spawn
  `README.md`, `ARCHITECTURE.md`, etc. on your own.
- **Don't commit model files.** They're in `.gitignore` for a reason
  (`.litertlm` is GiB-scale).
- **Keep `LlmEngine` the single concurrency boundary.** Don't add
  request-level locks in the server module — the engine already does it.
- **Server changes need device verification.** Type-checking is not enough
  for SSE / OpenAI-client compatibility. After changes to
  `OpenAiServer.kt`, exercise both `stream=false` and `stream=true` against
  a real client (`curl` is fine; the official `openai` Python SDK is
  better) before declaring done. If you can't test on-device, say so
  explicitly instead of claiming success.
