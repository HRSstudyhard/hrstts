---
license: apache-2.0
base_model: Qwen/Qwen3-TTS-12Hz-0.6B-Base
pipeline_tag: text-to-speech
language:
- en
- zh
- ja
- ko
- de
- fr
- es
- it
- pt
- ru
tags:
- litert
- tflite
- text-to-speech
- voice-cloning
- on-device
base_model_relation: quantized
---

LiteRT is Google's on-device runtime, the new name for TensorFlow Lite (Android: `com.google.ai.edge.litert:litert`), and `litert-torch`, the renamed `ai-edge-torch`, is its PyTorch converter: a PyTorch model converted unmodified with `litert_torch.convert` matched the original to 4e-7 on a Galaxy S26 ([measured](https://github.com/john-rocky/apple-silicon-llm-bench/blob/main/results/android/tinyhybridnet-three-runtimes.md), LiteRT 2.2.0, Android 16, 2026-09-05).

# Qwen3-TTS-12Hz-0.6B-Base — LiteRT

[Qwen3-TTS-12Hz-0.6B-Base](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base) (Apache-2.0) converted to LiteRT (.tflite) for fully on-device text-to-speech with 3-second voice cloning, in 10 languages at 24 kHz.

Qwen3-TTS is a speech LM: a Qwen3-style talker predicts 12.5 Hz frames of 16 codec tokens (first codebook by the talker, 15 residual codebooks by an inner "MTP" transformer), and a neural codec decoder renders PCM. LiteRT-LM's Engine decode loop does not support this generation structure yet, so the model runs as **three LiteRT graphs driven by a host-side loop** (LiteRT Compiled Model pattern). A complete Python reference pipeline and all conversion scripts live in the litert-samples sample: [`compiled_model_api/text_to_speech_lm`](https://github.com/john-rocky/litert-samples/tree/qwen3-tts-sample/compiled_model_api/text_to_speech_lm).

## Quick start (Python, desktop)

```bash
git clone -b qwen3-tts-sample https://github.com/john-rocky/litert-samples.git
cd litert-samples/compiled_model_api/text_to_speech_lm/python
pip install -r requirements.txt
python synthesize.py --text "Hello from LiteRT running fully on device." --output hello.wav
```

The script downloads this repository automatically (~1.4 GB for the default int4 configuration) and speaks in the bundled demo voice. Enroll your own voice from ~3 s of audio with the sample's `conversion/extract_speaker_embedding.py`, then pass `--speaker my_voice.npy`.

## Android app

The Android app (Kotlin, LiteRT CompiledModel API, CPU) lives in the litert-samples repository under [`samples/litert/text_to_speech_lm/kotlin_cpu/android`](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/text_to_speech_lm/kotlin_cpu/android): build with `./gradlew :app:installDebug`, then run `./install_to_device.sh` to download the model files from this repository and push them to the phone. The app on `main` loads the reference graphs, `mtp_fp32` + `codec_decoder_fp32`; the change that makes it use `mtp_folded_int8` and `codec_partA`/`codec_partB` when they are installed is in review at [litert-samples #354](https://github.com/google-ai-edge/litert-samples/pull/354).

## Files

| File | Size | Role |
|---|---|---|
| `talker_int4.tflite` | 256 MB | Talker LM (28-layer Qwen3, prefill_32/prefill_128/decode signatures, KV 1024), blockwise-32 OCTAV int4 weights |
| `talker_fp32.tflite` | 1.8 GB | fp32 talker; under greedy decoding it reproduces the PyTorch reference token-for-token |
| `mtp_fp32.tflite` | 440 MB | MTP decode step (5-layer transformer, 17-slot KV cache, 15 lm_heads), invoked 17× per frame — the exact reference graph |
| `mtp_folded_int8.tflite` | 218 MB | **Fast MTP**: all 16 inner steps × 5 layers folded into one graph (in-graph argmax + embedding gather, KV internal), GPTQ dynamic-int8 weights. One invoke per frame; ~5× faster on device. Drop-in replacement for `mtp_fp32.tflite` |
| `codec_decoder_fp32.tflite` | 457 MB | Codec decoder (RVQ + 8-layer transformer + causal ConvNet, 64-frame chunks → 24 kHz PCM) |
| `codec_partA.tflite` / `codec_partB.tflite` | 163 + 294 MB | **Fast codec**: the decoder split at the transformer/convnet boundary. Part A (transformer) runs fp32; Part B (the conv upsampler, ~all the FLOPs) runs with XNNPACK FORCE_FP16 → ~2.5× on device, ASR-identical. Drop-in replacement for `codec_decoder_fp32.tflite` |
| `tokenizer.json` | 11 MB | Qwen2 BPE tokenizer (Python sample / `tokenizers`) |
| `vocab.json`, `merges.txt` | 4.5 MB | Same vocabulary in raw form (used by the Android app's Kotlin tokenizer) |
| `tables/*` | 723 MB | Host-side embedding tables: codec embedding (fp32), 15 MTP embeddings (fp16), text embedding (fp16), text projection MLP (fp32) |
| `voices/demo_speaker.npy` | 4 KB | Demo voice x-vector (enrolled from the official Qwen3-TTS demo clip) |

## Accuracy

- Each graph is numerically verified against the PyTorch reference: talker bit-exact at torch level and correlation 1.0 / top-1 100% as .tflite; MTP 15/15 greedy tokens; codec decoder correlation 1.0 (max abs diff 1.8e-5).
- End to end with `talker_fp32` + greedy: token-for-token identical codes to the reference implementation, waveform correlation 1.000000, ASR round-trip returns the input sentence.
- `talker_int4` (data-free blockwise-32 OCTAV) produces a different but valid sampling trajectory; outputs transcribe identically under ASR round-trip. Channelwise int8/int4 quantization (the tooling default) degenerates on this model family — use blockwise granularity.

## Performance (Apple M4 Max, CPU/XNNPACK)

| Stage | per 80 ms audio frame |
|---|---|
| Talker decode (8 threads) | 45–50 ms |
| MTP inner loop (17 invokes, 1 thread) | ~148 ms |
| Codec decoder (amortized) | ~10 ms |
| Total | ~205 ms → RTF ≈ 2.5 |

The MTP inner loop dominates (a 78M-parameter transformer streams its weights 17 times per frame). `mtp_folded_int8.tflite` folds those 17 invokes into one graph and quantizes it: on the M4 Max the MTP drops to ~41 ms/frame, and on a Pixel 8a from ~333 to ~68 ms/frame. The fold is token-identical to the reference; the dynamic-int8 weights give a different-but-intelligible trajectory (ASR round-trip exact). The codec then dominates, and `codec_partA`/`codec_partB` split it so the conv-heavy back half runs in fp16 (~2.5× on device). Together the end-to-end RTF drops from ≈6.7 to ≈2.06 on a Pixel 8a (≈1.44 on M4 Max), ASR-lossless. Conversion scripts: [`export_mtp_folded.py` / `gptq_mtp_folded.py` / `export_codec_split.py`](https://github.com/john-rocky/hf-to-litertlm/tree/main/qwen3tts_work). Remaining lever: the talker (now ~52 ms/frame).

### Android (Pixel 8a)

Android figures use the standard TFLite [`benchmark_model`](https://ai.google.dev/edge/litert/models/measurement) on a **Pixel 8a** (Tensor G3, Android 16) — 5 warm-up runs then 20 timed runs, CPU at 4 threads.

| Graph | GPU (OpenCL) | CPU (XNNPACK, 4 threads) |
|---|---|---|
| `mtp_folded_int8.tflite` | 225 ms | 153 ms |
| `mtp_fp32.tflite` | 113 ms | 27 ms |

Nothing here is faster on the GPU on the Pixel 8a, so run this pipeline on the CPU on that phone. On a Snapdragon phone the picture differs — the Hexagon NPU runs `mtp_fp32.tflite` at 4.33 ms and `codec_partA.tflite` at 2.29 ms, and the GPU runs `talker_fp32.tflite`; see the Snapdragon NPU (Hexagon) section below.

## Snapdragon NPU (Hexagon)

- `codec_decoder_fp32.tflite` — neither accelerator produced a usable row on the S26. NPU: the benchmark process was killed, most likely out of memory. GPU: `LiteRtException: Failed to compile model`.
- `codec_partA.tflite` — the NPU runs it at 2.29 ms. The GPU does not — `LiteRtException: Failed to compile model`.
- `codec_partB.tflite` — every attempt ran while the phone was already warm, so no row meets the sweep's thermal condition. Not reported.
- `mtp_folded_int8.tflite` — neither accelerator produced a usable row on the S26. Both ended the same way: `LiteRtException: Failed to compile model`.
- `mtp_fp32.tflite` — the NPU runs it at 4.33 ms. The GPU does not — `LiteRtException: Failed to compile model`.
- `talker_fp32.tflite` — the GPU runs it at 127.6 ms. The NPU does not — `LiteRtException: Failed to compile model`.
- `talker_int4.tflite` — neither accelerator produced a usable row on the S26. NPU: the request fell back to the CPU (XNNPACK) without saying so, which leaves no number attributable to the NPU. GPU: the benchmark process was killed, most likely out of memory.

| file | backend | compiled | inference (median / min) | load |
|---|---|---|---:|---:|
| `codec_partA.tflite` | NPU (Hexagon v81) | on-device JIT | 2.29 ms / 2.24 ms | 152 ms |
| `mtp_fp32.tflite` | NPU (Hexagon v81) | on-device JIT | 4.33 ms / 4.20 ms | 240 ms |
| `talker_fp32.tflite` | GPU (Adreno) | — | 127.6 ms / 110.8 ms | 8998 ms |

Measured on a **Samsung Galaxy S26** (Snapdragon 8 Elite Gen 5 / SM8850, Hexagon v81, Android 16) with LiteRT `CompiledModel` 2.2.0, one accelerator per process, 5 warm-up runs then N=50 timed runs, median reported. Every run held thermal status `NONE` throughout. Headroom 0.62–0.79, where 1.0 is the throttling threshold.

**The NPU rows ran the published file unchanged.** LiteRT compiled it for the Hexagon on the device at first load. Those first compiles took 2.7 s to 5.7 s here. The `load` column above is the cached load every later run pays. Recipe and the runtime libraries it needs: [NPU guide](https://github.com/john-rocky/hf-to-litertlm/blob/main/docs/android-npu.md).

GPU wiring: [GPU guide](https://github.com/john-rocky/hf-to-litertlm/blob/main/docs/android-gpu.md).

## Limitations

- Voice cloning is x-vector mode only (speaker embedding). ICL-mode cloning (reference transcript + codec encoding of the reference audio) additionally needs the codec encoder, which is kept off-device (enrollment-time PyTorch).
- The prompt prefill is capped at 32 positions (the x-vector prompt is always 10); the KV cache is 1024 (~80 s of audio), generation is capped at 512 frames (~41 s) in the sample.
- Streaming synthesis (the model's dual-track design supports it) is not implemented in the sample loop yet.

## Raspberry Pi 5 (CPU)

Measured on a Raspberry Pi 5 Model B Rev 1.1 (8 GB, Raspberry Pi OS 64-bit) with the LiteRT [`benchmark_model`](https://ai.google.dev/edge/litert/models/measurement) tool from litert-cli-nightly 0.2.0.dev20260805: CPU inference (XNNPACK, 4 threads), 3 invocations per file of 10 warm-up plus 50 timed runs (the tool caps a phase at 150 s, so very slow graphs run fewer — the Runs column is the actual timed total). The latency is the median across invocations; the spread is the min–max over all timed runs. No thermal throttling occurred during these runs (`vcgencmd get_throttled` stayed `0x0`).

| File | Signature | Inference (median) | Spread (min–max) | Runs | Peak memory |
|---|---|---:|---:|---:|---:|
| `codec_decoder_fp32.tflite` | — | 9,706.5 ms | 9,450.6–9,888.7 ms | 48 | 1097 MB |
| `codec_partA.tflite` | — | 40.8 ms | 40.2–42.6 ms | 150 | 273 MB |
| `codec_partB.tflite` | — | 9,726.6 ms | 9,614.6–10,025.2 ms | 48 | 842 MB |
| `mtp_folded_int8.tflite` | — | 189.9 ms | 183.1–199.5 ms | 150 | 1384 MB |
| `mtp_fp32.tflite` | — | 50.5 ms | 49.7–55.7 ms | 150 | 743 MB |
| `talker_int4.tflite` | `decode` | 158.5 ms | 156.6–161.3 ms | 150 | 1462 MB |
| `talker_int4.tflite` | `prefill_128` | 1,362.7 ms | 1,349.5–1,399.2 ms | 150 | 1480 MB |
| `talker_int4.tflite` | `prefill_32` | 345.0 ms | 343.5–349.0 ms | 150 | 1466 MB |

## License

Apache-2.0, inherited from the base model by the Qwen team, Alibaba Group.
