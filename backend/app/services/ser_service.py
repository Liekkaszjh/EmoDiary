import json
import os
import sys
import time
from pathlib import Path
from typing import Dict

import numpy as np
import torch
import torch.nn.functional as F

from app.config import settings

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
os.environ.setdefault("OMP_NUM_THREADS", "1")

ROOT = settings.root_dir
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

TARGET_NAMES = ["Neutral", "Sad", "Angry", "Happy", "Fear", "Surprise"]


class SERService:
    def __init__(self):
        self._model = None
        device_pref = os.getenv("SER_DEVICE", "cpu").strip().lower()
        if device_pref == "auto":
            device = "cuda" if torch.cuda.is_available() else "cpu"
        elif device_pref.startswith("cuda"):
            device = device_pref if torch.cuda.is_available() else "cpu"
        else:
            device = "cpu"
        self._device = torch.device(device)
        self._extractor = None
        self._load_wav = None
        self._segment = None
        self._models = None
        self._init_error = ""
        print(f"[SER] device: {self._device}")

    @staticmethod
    def _zero_probs() -> Dict[str, float]:
        return {name: 0.0 for name in TARGET_NAMES}

    def _init_runtime(self):
        if self._extractor is not None or self._init_error:
            return
        try:
            from process_CASIA import FeatureExtractor, load_wav, segment
            import models

            self._load_wav = load_wav
            self._segment = segment
            self._models = models
            self._extractor = FeatureExtractor(
                sample_rate=16000,
                nmfcc=26,
                hop_length=512,
                target_frames=57,
                segment_length=1.8,
            )
        except Exception as exc:
            if isinstance(exc, ModuleNotFoundError) and getattr(exc, "name", "") == "python_speech_features":
                self._init_error = (
                    "Missing dependency: python_speech_features. "
                    "Install with: pip install python_speech_features scipy"
                )
            else:
                self._init_error = str(exc)

    @staticmethod
    def _replace_last_linear_to_num_classes(model: torch.nn.Module, num_classes: int) -> None:
        last_name = None
        last_layer = None
        for name, module in model.named_modules():
            if isinstance(module, torch.nn.Linear):
                last_name = name
                last_layer = module
        if last_name is None or last_layer is None:
            return
        if last_layer.out_features == num_classes:
            return
        parts = last_name.split(".")
        parent = model
        for part in parts[:-1]:
            parent = getattr(parent, part)
        setattr(parent, parts[-1], torch.nn.Linear(last_layer.in_features, num_classes))

    @staticmethod
    def _prepare_state_dict(raw_state):
        if isinstance(raw_state, dict) and "state_dict" in raw_state and isinstance(raw_state["state_dict"], dict):
            raw_state = raw_state["state_dict"]
        if not isinstance(raw_state, dict):
            raise TypeError("Checkpoint format is invalid: expected state_dict-like mapping.")
        cleaned = {}
        for key, val in raw_state.items():
            if not hasattr(val, "shape"):
                continue
            cleaned[key[7:] if key.startswith("module.") else key] = val
        return cleaned

    def _init_model(self, shape):
        self._init_runtime()
        if self._init_error:
            return
        if self._model is not None:
            return
        try:
            print(f"[SER] loading checkpoint: {settings.ser_model_path}")
            t0 = time.time()
            model = self._models.CTMAM_EMODB(shape=shape)
            self._replace_last_linear_to_num_classes(model, len(TARGET_NAMES))
            print(f"[SER] model built in {time.time() - t0:.2f}s")

            t1 = time.time()
            raw_state = torch.load(settings.ser_model_path, map_location="cpu")
            state_dict = self._prepare_state_dict(raw_state)
            print(f"[SER] checkpoint loaded in {time.time() - t1:.2f}s")

            t2 = time.time()
            model.load_state_dict(state_dict)
            print(f"[SER] state_dict applied in {time.time() - t2:.2f}s")

            t3 = time.time()
            model.to(self._device)
            model.eval()
            print(f"[SER] model moved to {self._device} and ready in {time.time() - t3:.2f}s")
            self._model = model
        except Exception as exc:
            self._init_error = str(exc)
            print(f"[SER unavailable] {self._init_error}")

    def infer(self, audio_path: Path) -> Dict[str, float]:
        try:
            self._init_runtime()
            if self._init_error:
                print(f"[SER unavailable] {self._init_error}")
                return self._zero_probs()

            try:
                wav = self._load_wav(str(audio_path), target_sr=16000)
            except Exception:
                # Fallback for formats like m4a/aac where soundfile may fail on Windows.
                import librosa

                wav, _ = librosa.load(str(audio_path), sr=16000, mono=True)
                wav = np.asarray(wav, dtype=np.float32)

            xseg = self._segment(
                wav,
                sample_rate=16000,
                segment_length=1.8,
                overlap=1.6,
                padding=True,
            )
            if xseg is None or len(xseg) == 0:
                return self._zero_probs()
            print(f"[SER] segments: {len(xseg)}")

            if len(xseg) > 80:
                idx = np.linspace(0, len(xseg) - 1, num=80, dtype=np.int64)
                xseg = xseg[idx]
                print("[SER] segments capped to 80 for latency control")

            feats = self._extractor.get_features("mfcc", xseg)
            shape = feats.shape[1:]
            self._init_model(shape)
            if self._model is None:
                return self._zero_probs()

            with torch.no_grad():
                t4 = time.time()
                x = torch.from_numpy(feats).float().to(self._device)
                logits = self._model(x.unsqueeze(1))
                avg_logits = logits.mean(dim=0)
                probs = F.softmax(avg_logits, dim=0).cpu().numpy()
                probs = np.nan_to_num(probs, nan=0.0, posinf=1.0, neginf=0.0)
                s = float(probs.sum())
                if s > 0:
                    probs = probs / s
                else:
                    probs = np.zeros_like(probs)
                    probs[0] = 1.0
                print(f"[SER] forward done in {time.time() - t4:.2f}s")
            return {name: float(prob) for name, prob in zip(TARGET_NAMES, probs)}
        except Exception as exc:
            print(f"[SER error] {exc}")
            return self._zero_probs()

    def warmup(self) -> None:
        self._init_runtime()
        if not self._init_error:
            self._init_model((26, 57))

    @staticmethod
    def top_label(probs: Dict[str, float]) -> str:
        if not probs:
            return "Neutral"
        return max(probs.items(), key=lambda x: x[1])[0]

    @staticmethod
    def to_json(probs: Dict[str, float]) -> str:
        return json.dumps(probs, ensure_ascii=False, allow_nan=False)


ser_service = SERService()
