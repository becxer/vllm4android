#!/usr/bin/env python3
"""End-to-end smoke test against a running vllm4android server.

This is the *real* compatibility check: it talks to the device with the
official OpenAI Python SDK, so any deviation from the spec (header,
chunk shape, error envelope, role ordering, [DONE] handling) shows up
the same way it would for a real OpenAI client.

Prereqs:
    - Server running on a device or emulator (Start in the app UI)
    - adb forward tcp:8080 tcp:8080  (or set BASE_URL to the device's LAN IP)
    - pip install 'openai>=1.0'

Usage:
    scripts/smoke.py
    BASE_URL=http://192.168.1.10:8080 scripts/smoke.py
    MODEL=gemma-4-E4B-it scripts/smoke.py
"""
from __future__ import annotations

import os
import sys

try:
    from openai import OpenAI, BadRequestError
except ImportError:
    sys.exit("openai SDK not installed. Run:  pip install 'openai>=1.0'")

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
MODEL = os.environ.get("MODEL", "gemma-4-E2B-it")
TIMEOUT = float(os.environ.get("TIMEOUT", "180"))

GREEN = "\033[32m"
RED = "\033[31m"
RESET = "\033[0m"


def passed(label: str) -> None:
    print(f"{GREEN}PASS{RESET} {label}")


def failed(label: str, details: str = "") -> None:
    print(f"{RED}FAIL{RESET} {label}", file=sys.stderr)
    if details:
        print(f"       {details}", file=sys.stderr)
    sys.exit(1)


client = OpenAI(base_url=f"{BASE_URL}/v1", api_key="not-needed", timeout=TIMEOUT)


def test_models() -> None:
    models = client.models.list()
    ids = [m.id for m in models.data]
    if MODEL not in ids:
        failed("/v1/models", f"{MODEL!r} not in {ids}")
    passed(f"/v1/models lists {MODEL}")


def test_non_stream() -> None:
    resp = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Reply with exactly one short word."}],
    )
    if not resp.id.startswith("chatcmpl-"):
        failed("non-stream", f"id format: {resp.id!r}")
    if resp.object != "chat.completion":
        failed("non-stream", f"object: {resp.object!r}")
    if resp.model != MODEL:
        failed("non-stream", f"echoed model: {resp.model!r}")
    if not resp.choices:
        failed("non-stream", "no choices")
    choice = resp.choices[0]
    if choice.message.role != "assistant":
        failed("non-stream", f"role: {choice.message.role!r}")
    if not (choice.message.content or "").strip():
        failed("non-stream", "empty content")
    if choice.finish_reason != "stop":
        failed("non-stream", f"finish_reason: {choice.finish_reason!r}")
    passed(f"chat.completions.create(stream=False) → {choice.message.content!r}")


def test_stream() -> None:
    stream = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Reply with exactly two short words."}],
        stream=True,
    )
    chunks = list(stream)
    if not chunks:
        failed("stream", "no chunks received")

    # Object/model consistency across chunks.
    bad_obj = [c for c in chunks if c.object != "chat.completion.chunk"]
    if bad_obj:
        failed("stream", f"chunks with wrong object: {bad_obj[0]}")
    if {c.id for c in chunks} != {chunks[0].id}:
        failed("stream", f"chunks span multiple ids: {[c.id for c in chunks]}")
    if {c.model for c in chunks} != {MODEL}:
        failed("stream", f"chunks span multiple models: {[c.model for c in chunks]}")

    # Role chunk first (OpenAI convention).
    role_chunks = [c for c in chunks if c.choices and c.choices[0].delta.role]
    if not role_chunks:
        failed("stream", "no chunk emitted delta.role")
    if role_chunks[0] is not chunks[0]:
        failed("stream", "role chunk is not the first chunk")
    if role_chunks[0].choices[0].delta.role != "assistant":
        failed("stream", f"first role: {role_chunks[0].choices[0].delta.role!r}")

    # finish_reason=stop appears exactly once, on the last chunk.
    finish_idx = [
        i for i, c in enumerate(chunks) if c.choices and c.choices[0].finish_reason
    ]
    if finish_idx != [len(chunks) - 1]:
        failed("stream", f"finish_reason positions: {finish_idx}, expected [{len(chunks) - 1}]")
    if chunks[-1].choices[0].finish_reason != "stop":
        failed("stream", f"final finish_reason: {chunks[-1].choices[0].finish_reason!r}")

    # Concatenated content matches what the SDK would yield.
    text = "".join((c.choices[0].delta.content or "") for c in chunks if c.choices)
    if not text.strip():
        failed("stream", "concatenated delta content is empty")

    passed(f"chat.completions.create(stream=True) → {len(chunks)} chunks → {text!r}")


def test_empty_messages() -> None:
    try:
        client.chat.completions.create(model=MODEL, messages=[])
    except BadRequestError as e:
        msg = (getattr(e, "message", None) or str(e)).lower()
        if "messages" not in msg:
            failed("empty messages", f"error doesn't mention messages: {e}")
        passed(f"empty messages → 400 ({type(e).__name__})")
        return
    except Exception as e:  # noqa: BLE001
        failed("empty messages", f"unexpected error type {type(e).__name__}: {e}")
    failed("empty messages", "expected BadRequestError, got success")


def test_system_message() -> None:
    """A system message must influence behavior — at minimum, the call
    must succeed and return non-empty content (we don't assert on the
    content itself since the model is non-deterministic)."""
    resp = client.chat.completions.create(
        model=MODEL,
        messages=[
            {"role": "system", "content": "Always answer in exactly three words."},
            {"role": "user", "content": "Describe the sky."},
        ],
    )
    content = (resp.choices[0].message.content or "").strip()
    if not content:
        failed("system message", "empty content")
    passed(f"system+user → {content!r}")


def test_sampler_params() -> None:
    """Sampler params must be accepted (not 400). We don't assert on the
    output distribution since the engine TODO still maps these through."""
    resp = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "Say 'ok'."}],
        temperature=0.7,
        top_p=0.95,
        max_tokens=16,
    )
    if not resp.choices:
        failed("sampler", "no choices")
    passed("sampler params accepted (temperature, top_p, max_tokens)")


def main() -> None:
    print(f"Smoke test → {BASE_URL}  model={MODEL}\n")
    test_models()
    test_empty_messages()
    test_non_stream()
    test_stream()
    test_system_message()
    test_sampler_params()
    print(f"\n{GREEN}All smoke tests passed.{RESET}")


if __name__ == "__main__":
    main()
