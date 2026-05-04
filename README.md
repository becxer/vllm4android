# vllm4android

A vLLM-style on-device LLM server for Android. Loads **Gemma 4 E2B IT**
([`litert-community/gemma-4-E2B-it-litert-lm`][model]) via [LiteRT-LM][litertlm]
and exposes an **OpenAI Chat Completions–compatible** HTTP API on the phone, so
existing OpenAI SDK clients can point at the device as their backend.

Multimodal (text + image) supported. SSE streaming supported. No paid API,
no cloud — the model runs on the device.

[litertlm]: https://github.com/google-ai-edge/LiteRT-LM
[model]: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

> **What this is not.** This is not a port of vLLM's PagedAttention or
> continuous batching — those make no sense for a single-user mobile
> device. The "vLLM-like" parts kept here: the OpenAI REST surface, SSE
> streaming, model warm-loading at app startup.

---

## Quick demo

Once installed and started on a device with `adb forward tcp:8080 tcp:8080`:

```bash
$ curl http://localhost:8080/v1/chat/completions \
    -H 'Content-Type: application/json' \
    -d '{"model":"gemma-4-E2B-it",
         "messages":[{"role":"user","content":"Say hi in three words."}]}'

{"id":"chatcmpl-…","object":"chat.completion","created":1714800000,
 "model":"gemma-4-E2B-it",
 "choices":[{"index":0,
             "message":{"role":"assistant","content":"Hi there friend!"},
             "finish_reason":"stop"}],
 "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}
```

Or with the official OpenAI Python SDK — same code you'd use against
`api.openai.com`, just retargeted at your phone:

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")

resp = client.chat.completions.create(
    model="gemma-4-E2B-it",
    messages=[{"role": "user", "content": "Say hi in three words."}],
)
print(resp.choices[0].message.content)
```

---

## Prerequisites

- **Android device or emulator**, API 31+ (Android 12+).
  - ≥ 4 GB RAM recommended (the model is ~2.6 GB).
  - First launch downloads the model; you'll need ~3 GB free internal
    storage and a working internet connection.
- **JDK 17** on the build machine.
- **Android SDK** with platform-tools (`adb`).
- **Gradle 8.11+** for the *first* build (only to generate the wrapper —
  see [Gradle wrapper](#gradle-wrapper) below).
- **Optional:** Python 3.8+ with `pip install 'openai>=1.0'` if you want
  to run the Python smoke tests.

---

## Setup

### 1. Clone

```bash
git clone https://github.com/becxer/vllm4android.git
cd vllm4android
```

### 2. Gradle wrapper

This repo does **not** ship `gradlew` yet. Generate it once on a machine
that has Gradle installed:

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```

After this, `./gradlew` works without needing system Gradle.

### 3. Build & install on a device

Connect a device (or boot an emulator) so `adb devices` lists it, then:

```bash
./gradlew :app:installDebug
```

The first build pulls the LiteRT-LM, Ktor, and Compose dependencies — give
it a few minutes.

---

## Run the server

1. Open the **vllm4android** app on the device.
2. Tap **Start server**. The notification shows progress:
   - **Downloading model…** (~2.6 GB from Hugging Face — one time only,
     cached under `filesDir`)
   - **Loading model into memory…** (~10 s on most devices)
   - **Serving on port 8080**
3. From your computer, forward the port:

   ```bash
   adb forward tcp:8080 tcp:8080
   ```

   (Or skip the forward and hit the device's LAN IP directly.)

That's it — you have an OpenAI-compatible endpoint at
`http://localhost:8080/v1`.

---

## Use the API

### List the model

```bash
curl http://localhost:8080/v1/models
```

```json
{"object":"list","data":[{"id":"gemma-4-E2B-it","object":"model",
                          "created":1714800000,"owned_by":"vllm4android"}]}
```

### Chat completion (non-streaming)

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma-4-E2B-it",
       "messages":[
         {"role":"system","content":"You are concise."},
         {"role":"user","content":"What is the capital of France?"}
       ]}'
```

### Chat completion (streaming, SSE)

Add `"stream": true` and you get OpenAI's SSE format:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma-4-E2B-it",
       "messages":[{"role":"user","content":"Tell me a one-line haiku."}],
       "stream":true}'
```

```
data: {"id":"chatcmpl-…","object":"chat.completion.chunk",…,"choices":[{"index":0,"delta":{"role":"assistant"}}]}
data: {"id":"chatcmpl-…",…,"choices":[{"index":0,"delta":{"content":"Cherry"}}]}
data: {"id":"chatcmpl-…",…,"choices":[{"index":0,"delta":{"content":" blossoms"}}]}
…
data: {"id":"chatcmpl-…",…,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
data: [DONE]
```

### Multimodal (image input)

Gemma 4 IT is multimodal. Send images as base64-encoded `data:` URIs in
an OpenAI-style `image_url` content part:

```python
import base64
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")

with open("cat.jpg", "rb") as f:
    b64 = base64.b64encode(f.read()).decode()

resp = client.chat.completions.create(
    model="gemma-4-E2B-it",
    messages=[{
        "role": "user",
        "content": [
            {"type": "text", "text": "What's in this image?"},
            {"type": "image_url",
             "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
        ],
    }],
)
print(resp.choices[0].message.content)
```

> **v1 multimodal limits**
> - Image URLs **must be `data:` URIs**. The server does not fetch
>   external HTTP images.
> - One user turn (plus optional system messages) per request. Multi-turn
>   vision (history that includes earlier image responses) returns 400.

### Drop-in replacement for OpenAI

Anything that talks to `api.openai.com/v1` works the same way — just
change `base_url`. LangChain, LlamaIndex, the `openai-cli`, your own
script — all unchanged.

---

## Run the tests

There are two layers:

### JVM unit tests (no device, no model)

Validate the OpenAI HTTP contract using a `FakeEngine`. Fast.

```bash
./gradlew :server:test :engine:test
```

What's covered:
- SSE framing (`data:` prefix, blank-line separator, `[DONE]` terminator,
  shared chunk id, role-first / content / `finish_reason="stop"` ordering)
- OpenAI error envelope on `messages: []`
- Sampler params (`temperature`, `top_p`, `top_k`, `max_tokens`) reach
  the engine
- Multimodal data-URI bytes round-trip into `ContentPart.Image`
- HTTP image URLs and unknown content-part types → 400
- Gemma chat template (system folding, `assistant` → `model`)

### On-device end-to-end tests

After starting the server (see [Run the server](#run-the-server)) and
running `adb forward tcp:8080 tcp:8080`:

```bash
# curl + jq, no Python deps. Healthz, /v1/models, non-stream, stream,
# empty-messages 400.
scripts/test.sh

# Real OpenAI Python SDK. Adds system messages, sampler params, and
# image description on top of the curl checks.
pip install 'openai>=1.0'
scripts/test.py

# Substitute your own picture for a meaningful multimodal test:
IMAGE_PATH=path/to/cat.jpg scripts/test.py
```

Both honor `BASE_URL` (default `http://localhost:8080`) and `MODEL`
(default `gemma-4-E2B-it`).

---

## How it works

```
┌─────────────────────────────┐
│  app/                       │  Compose UI + foreground Service
│   MainActivity              │  (download → load → serve pipeline)
│   service/LlmServerService  │
└────────────┬────────────────┘
             │ depends on
┌────────────▼────────────────┐
│  server/                    │  Ktor (CIO) HTTP server
│   OpenAiServer              │  POST /v1/chat/completions
│   ContentDecoder            │  GET  /v1/models
│   dto/OpenAiDto             │  GET  /healthz
└────────────┬────────────────┘
             │ depends on
┌────────────▼────────────────┐
│  engine/                    │  LiteRT-LM wrapper
│   LlmEngine, ChatTurn       │  one Engine, one in-flight gen (Mutex)
│   ChatTemplate              │  Gemma <start_of_turn>... template
│   ModelDownloader           │  HF first-run download
└────────────┬────────────────┘
             │ depends on
┌────────────▼────────────────┐
│  com.google.ai.edge.litertlm │  LiteRT-LM Engine + Conversation
│       :litertlm-android      │  (XNNPACK CPU backend by default)
└──────────────────────────────┘
```

- `app → server → engine`. Only the engine module links to
  `com.google.ai.edge.litertlm:litertlm-android`.
- One `LlmEngine` per process, generations serialized with a `Mutex` —
  there's no point queueing concurrent generations on a single phone.
- The HTTP server lives inside a foreground `Service` (Android 12+
  requires this for long-running networking).
- Text-only requests render through the Gemma chat template before
  hitting LiteRT-LM. Multimodal requests go straight to
  `Conversation.sendMessage(Contents.of(...))` — LiteRT-LM does the
  multimodal templating internally.

For deeper notes on conventions and "where do I add X", see
[CLAUDE.md](./CLAUDE.md).

---

## Status & known gaps

- ✅ Text chat completions, streaming + non-streaming
- ✅ Gemma chat template (system messages, multi-turn)
- ✅ Multimodal (image input via `data:` URIs)
- ✅ JVM unit tests for OpenAI compat
- ✅ End-to-end smoke scripts (curl + Python SDK)
- ⏳ **Sampler params** (`temperature`, `top_p`, `top_k`, `max_tokens`) flow
  through the API surface but are **not yet** mapped to LiteRT-LM
  `SamplerConfig`. The values are silently ignored at the engine.
  ([`LlmEngine.kt` TODO][sampler-todo])
- ⏳ **Multi-turn vision** (history including earlier image responses) —
  rejected with 400. Needs LiteRT-LM `Conversation` history replay.
- ⏳ **GPU / NPU backends** — code uses `Backend.CPU()` (XNNPACK, 4
  threads). GPU/NPU variants exist on the model repo and require loading
  a different `.litertlm` artifact, not just a `Backend` change.
- ⏳ **Gradle wrapper** not committed yet.

[sampler-todo]: engine/src/main/java/com/vllm4android/engine/LlmEngine.kt

---

## Troubleshooting

**Download stalls or fails.** The model is ~2.6 GB. Some networks rate-
limit Hugging Face. Re-tap **Start server** to resume — the downloader
writes to a `.part` file and atomically renames on completion, so partial
downloads aren't reused (re-download from scratch on retry).

**`adb forward` fails or curl times out.** Check `adb devices` lists your
device. If on a Mac/Linux, `adb forward tcp:8080 tcp:8080` must run
*after* the server reaches the **Serving on port 8080** state.

**LiteRT-LM crashes on startup.** Make sure your device has enough free
RAM. Gemma 4 E2B IT needs roughly 3–4 GB of process memory at runtime.

**App is killed in the background.** The server runs in a foreground
service, but aggressive OEM battery savers (Xiaomi, Huawei, Samsung) may
still kill it. Whitelist the app in the device's battery settings.

---

## Project layout

```
.
├── app/                       # Android app (Compose UI, foreground service)
├── server/                    # Ktor HTTP server (OpenAI routes + DTOs)
├── engine/                    # LiteRT-LM wrapper, chat template, downloader
├── scripts/
│   ├── test.sh                # curl + jq smoke test
│   └── test.py                # OpenAI Python SDK smoke test
├── gradle/libs.versions.toml  # version catalog
├── settings.gradle.kts
├── build.gradle.kts
├── CLAUDE.md                  # contributor guide for AI assistants
└── README.md                  # this file
```

---

## Acknowledgements

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — Google AI
  Edge's on-device LLM runtime.
- [`litert-community/gemma-4-E2B-it-litert-lm`][model] — Gemma 4 E2B IT
  in `.litertlm` format.
- [Ktor](https://ktor.io/) — the embedded HTTP server.
- [vLLM](https://github.com/vllm-project/vllm) — for the OpenAI-
  compatible API contract this project apes.
