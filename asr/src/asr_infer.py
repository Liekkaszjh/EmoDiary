# src/asr_infer.py
"""
Clean, reproducible SenseVoiceSmall ASR inference script (FunASR).

- Downloads/caches models under ./models_cache (repo-local).
- No custom remote_code; avoids "Loading remote code failed" noise.
- Optional: plain-text output (strip emoji / special markers).
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Iterable, List, Optional

# -----------------------------
# Repo-local cache (model weights go under ./models_cache)
# -----------------------------
ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "models_cache"
os.environ.setdefault("MODELSCOPE_CACHE", str(CACHE))
os.environ.setdefault("HF_HOME", str(CACHE / "huggingface"))
os.environ.setdefault("TRANSFORMERS_CACHE", str(CACHE / "huggingface" / "transformers"))
os.environ.setdefault("TORCH_HOME", str(CACHE / "torch"))

from funasr import AutoModel
from funasr.utils.postprocess_utils import rich_transcription_postprocess
from tqdm import tqdm


AUDIO_EXTS = {".wav", ".mp3", ".flac", ".m4a", ".aac", ".ogg"}


# A conservative emoji remover (covers common emoji blocks).
_EMOJI_RE = re.compile(
    "["
    "\U0001F300-\U0001F5FF"  # symbols & pictographs
    "\U0001F600-\U0001F64F"  # emoticons
    "\U0001F680-\U0001F6FF"  # transport & map symbols
    "\U0001F700-\U0001F77F"  # alchemical symbols
    "\U0001F780-\U0001F7FF"  # geometric extended
    "\U0001F800-\U0001F8FF"  # arrows-c
    "\U0001F900-\U0001F9FF"  # supplemental symbols
    "\U0001FA00-\U0001FAFF"  # symbols & pictographs extended-A
    "\U00002700-\U000027BF"  # dingbats
    "\U00002600-\U000026FF"  # misc symbols
    "]+",
    flags=re.UNICODE,
)

# Some ASR toolchains emit special markup tokens like <|...|>
_SPECIAL_MARKUP_RE = re.compile(r"<\|.*?\|>")


def list_audio_files(p: Path) -> List[Path]:
    """Collect audio files from a file path or directory (recursive)."""
    if p.is_file():
        return [p]
    files: List[Path] = []
    for ext in AUDIO_EXTS:
        files.extend(p.rglob(f"*{ext}"))
    return sorted(set(files))


def to_plain_text(text: str) -> str:
    """Strip common non-text artifacts (emoji / special markup)."""
    text = _SPECIAL_MARKUP_RE.sub("", text)
    text = _EMOJI_RE.sub("", text)
    # Collapse excessive whitespace
    text = re.sub(r"\s+", " ", text).strip()
    return text


def resolve_device(device: str, strict: bool = False) -> str:
    """Resolve device string; optionally fallback to CPU if CUDA unavailable."""
    dev = device.strip().lower()
    if dev.startswith("cuda"):
        try:
            import torch  # local import to keep dependency minimal

            if not torch.cuda.is_available():
                msg = f"CUDA requested ({device}) but torch.cuda.is_available() is False."
                if strict:
                    raise RuntimeError(msg)
                print(f"[WARN] {msg} Falling back to CPU.")
                return "cpu"
        except Exception as e:
            if strict:
                raise
            print(f"[WARN] Failed to validate CUDA availability ({e}). Using requested device: {device}")
    return device


def build_model(
    model_id: str,
    device: str,
    disable_update: bool,
    use_vad: bool,
    vad_max_single_segment_ms: int,
    trust_remote_code: bool,
):
    """Build FunASR AutoModel with optional VAD."""
    kwargs = dict(
        model=model_id,
        device=device,
        disable_update=disable_update,
        trust_remote_code=trust_remote_code,
    )
    if use_vad:
        kwargs.update(
            vad_model="fsmn-vad",
            vad_kwargs={"max_single_segment_time": int(vad_max_single_segment_ms)},
        )
    return AutoModel(**kwargs)


def generate_one(
    model,
    audio_path: Path,
    language: str,
    use_itn: bool,
    use_vad: bool,
    batch_size_s: int,
    merge_length_s: int,
):
    """Run ASR for a single audio file and return (raw_text, post_text)."""
    gen_kwargs = dict(
        input=str(audio_path),
        cache={},
        language=language,
        use_itn=use_itn,
        batch_size_s=int(batch_size_s),
    )
    if use_vad:
        gen_kwargs.update(
            merge_vad=True,
            merge_length_s=int(merge_length_s),
        )

    res = model.generate(**gen_kwargs)
    raw_text = (res[0] or {}).get("text", "")
    post_text = rich_transcription_postprocess(raw_text) if raw_text else ""
    return raw_text, post_text


def main():
    parser = argparse.ArgumentParser(description="SenseVoiceSmall ASR inference (FunASR, clean)")
    parser.add_argument("--input", type=str, required=True, help="Audio file path OR a directory (recursive).")
    parser.add_argument("--output", type=str, default="outputs/result.jsonl", help="Output jsonl path.")
    parser.add_argument("--language", type=str, default="zh", help="zh / auto / en / yue / ja / ko ...")
    parser.add_argument("--device", type=str, default="cuda:0", help="cuda:0 / cpu")
    parser.add_argument("--strict_device", action="store_true", help="If set, do NOT fallback to CPU when CUDA unavailable.")
    parser.add_argument("--model", type=str, default="iic/SenseVoiceSmall", help="Model id (ModelScope).")
    parser.add_argument("--use_itn", action="store_true", help="Enable ITN (recommended for cleaner numbers/dates).")
    parser.add_argument("--plain", action="store_true", help="Strip emoji / special markup from final text.")
    parser.add_argument("--trust_remote_code", action="store_true", help="Allow loading model remote code (recommended).")
    parser.add_argument("--disable_update", action="store_true", help="Disable FunASR update checks (faster startup).")

    # VAD controls
    parser.add_argument("--no_vad", action="store_true", help="Disable VAD (faster startup, but long audio may degrade).")
    parser.add_argument("--vad_max_single_segment_ms", type=int, default=30000, help="VAD max single segment (ms).")
    parser.add_argument("--batch_size_s", type=int, default=60, help="Dynamic batch size in seconds (FunASR).")
    parser.add_argument("--merge_length_s", type=int, default=15, help="Merge VAD segments length (s).")

    args = parser.parse_args()

    in_path = Path(args.input).expanduser().resolve()
    out_path = Path(args.output).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    audio_files = list_audio_files(in_path)
    if not audio_files:
        raise FileNotFoundError(f"No audio files found in: {in_path}")

    device = resolve_device(args.device, strict=args.strict_device)
    use_vad = not args.no_vad

    model = build_model(
        model_id=args.model,
        device=device,
        disable_update=bool(args.disable_update),
        use_vad=use_vad,
        vad_max_single_segment_ms=args.vad_max_single_segment_ms,
        trust_remote_code=bool(args.trust_remote_code),
    )

    with out_path.open("w", encoding="utf-8") as f:
        it: Iterable[Path] = audio_files
        if len(audio_files) > 1:
            it = tqdm(audio_files, desc="ASR", unit="file")

        for wav in it:
            raw_text, text = generate_one(
                model=model,
                audio_path=wav,
                language=args.language,
                use_itn=args.use_itn,
                use_vad=use_vad,
                batch_size_s=args.batch_size_s,
                merge_length_s=args.merge_length_s,
            )
            final_text = to_plain_text(text) if args.plain else text

            record = {"audio": str(wav), "text": final_text, "raw": raw_text}
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
            print(f"[OK] {wav.name}: {final_text}")


if __name__ == "__main__":
    main()
