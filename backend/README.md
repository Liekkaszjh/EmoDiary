# Backend

Emotion Diary FastAPI backend.

## Run

```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## Model integration

- ASR source: `E:\ASR0106\sensevoice-asr-repro\src\asr_infer.py`
- SER model (default): `data/CASIA/model_CASIA_CTMAM_EMODB_mfcc_CASIA_random80_20_seed2022_hop512.pth`
- You can override paths by environment variables:
  - `ASR_REPO_SRC`
  - `SER_MODEL_PATH`
  - `SER_DEVICE` (`cpu` default, `auto`, or `cuda:0`)

## Notes

- SER runtime needs `python_speech_features` and `scipy` (already included in `requirements.txt`).

## APIs

- `POST /auth/register`
- `POST /auth/login`
- `GET /users/me`
- `PUT /users/me`
- `POST /diaries/upload`
- `GET /diaries`
- `GET /diaries/{id}`
- `GET /analytics/insight?range_key=today|3d|7d|1m|6m|1y`
- `POST /analytics/agent-report` (`range_key`: `day|week|month`)
- `GET /analytics/agent-reports?range_key=day|week|month&limit=20`
- `POST /analytics/agent-chat` (`message`, `range_key`, `session_id`)

## Aliyun Agent setup

Set environment variables before startup:

- `DASHSCOPE_API_KEY` (or `ALIYUN_API_KEY`)
- `DASHSCOPE_APP_ID` (or `ALIYUN_AGENT_APP_ID`)
- Optional:
  - `DASHSCOPE_ENDPOINT` (default: `https://dashscope.aliyuncs.com`)
  - `DASHSCOPE_TIMEOUT_SECONDS` (default: `45`)

PowerShell example:

```powershell
$env:DASHSCOPE_API_KEY = "sk-xxxx"
$env:DASHSCOPE_APP_ID = "YOUR_APP_ID"
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

`/analytics/agent-report` will save generated summaries to `agent_reports` table, and clients can read history with `/analytics/agent-reports`.
