import os
import sys
from pathlib import Path

from app.config import settings


class ASRService:
    def __init__(self):
        self._model = None
        self._mod = None
        self._enabled = True
        self._init_error = ""

    @staticmethod
    def _device() -> str:
        try:
            import torch  # type: ignore

            return "cuda:0" if torch.cuda.is_available() else "cpu"
        except Exception:
            return "cpu"

    @staticmethod
    def _torchaudio_hint() -> str:
        torch_ver = "your_torch_version"
        try:
            import torch  # type: ignore

            torch_ver = getattr(torch, "__version__", torch_ver).split("+")[0]
        except Exception:
            pass
        return (
            "Missing dependency: torchaudio. "
            f"Install a matching build, e.g. `pip install torchaudio=={torch_ver}`."
        )

    def _init(self):
        if self._model is not None or self._init_error:
            return
        try:
            os.environ.setdefault("TQDM_DISABLE", "1")
            src = settings.asr_repo_src
            if not src.exists():
                raise FileNotFoundError(f"ASR source not found: {src}")
            sys.path.insert(0, str(src))
            import asr_infer  # type: ignore

            self._mod = asr_infer
            self._model = asr_infer.build_model(
                model_id="iic/SenseVoiceSmall",
                device=self._device(),
                disable_update=True,
                use_vad=True,
                vad_max_single_segment_ms=30000,
                trust_remote_code=False,
            )
        except ModuleNotFoundError as exc:
            self._enabled = False
            if getattr(exc, "name", "") == "torchaudio":
                self._init_error = self._torchaudio_hint()
            else:
                self._init_error = str(exc)
        except Exception as exc:
            self._enabled = False
            self._init_error = str(exc)

    def transcribe(self, audio_path: Path) -> str:
        self._init()
        if not self._enabled or self._mod is None:
            return f"[ASR unavailable] {self._init_error}"
        try:
            _, post = self._mod.generate_one(
                model=self._model,
                audio_path=audio_path,
                language="zh",
                use_itn=True,
                use_vad=True,
                batch_size_s=60,
                merge_length_s=15,
            )
            return self._mod.to_plain_text(post)
        except Exception as exc:
            return f"[ASR error] {exc}"

    def warmup(self) -> None:
        self._init()


asr_service = ASRService()
