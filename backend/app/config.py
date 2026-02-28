import os
from pathlib import Path


def _read_int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


class Settings:
    project_name = "Emotion Diary Backend"
    secret_key = "change-this-in-production"
    algorithm = "HS256"
    access_token_expire_minutes = 60 * 24 * 7

    root_dir = Path(__file__).resolve().parents[2]
    storage_dir = root_dir / "backend" / "storage"
    db_path = root_dir / "backend" / "emotion_diary.db"

    _default_asr_src = root_dir / "asr" / "src"
    asr_repo_src = Path(os.getenv("ASR_REPO_SRC", str(_default_asr_src)))
    _default_ser_model = root_dir / "data" / "CASIA" / "model_CASIA_CTMAM_EMODB_mfcc_CASIA_random80_20_seed2022_hop512.pth"
    ser_model_path = Path(os.getenv("SER_MODEL_PATH", str(_default_ser_model)))

    # Note: hardcoded by request for quick local setup.
    _hardcoded_dashscope_api_key = "sk-f94f31ed893d4ca280546005e12270cd"
    dashscope_api_key = (
        os.getenv("DASHSCOPE_API_KEY")
        or os.getenv("ALIYUN_API_KEY")
        or _hardcoded_dashscope_api_key
    ).strip()
    dashscope_app_id = (
        os.getenv("DASHSCOPE_APP_ID")
        or os.getenv("ALIYUN_AGENT_APP_ID")
        or "664ab26e0d65494b9d8b3565734072fc"
    ).strip()
    dashscope_endpoint = os.getenv("DASHSCOPE_ENDPOINT", "https://dashscope.aliyuncs.com").rstrip("/")
    dashscope_timeout_seconds = _read_int_env("DASHSCOPE_TIMEOUT_SECONDS", 45)


settings = Settings()
