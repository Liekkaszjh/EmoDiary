import time
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_db
from app.deps import get_current_user
from app.models import Diary, User
from app.schemas import AsrResponse, DiaryItem, DiaryListResponse
from app.services.asr_service import asr_service
from app.services.ser_service import ser_service


router = APIRouter(prefix="/diaries", tags=["diaries"])


def to_schema(item: Diary) -> DiaryItem:
    return DiaryItem(
        id=item.id,
        created_at=item.created_at,
        transcript=item.transcript,
        emotion_label=item.emotion_label,
        audio_path=item.audio_path,
    )


@router.post("/upload", response_model=DiaryItem)
async def upload_diary(
    audio: UploadFile = File(...),
    preserve_fields: bool = Form(False),
    transcript: Optional[str] = Form(None),
    emotion_label: Optional[str] = Form(None),
    created_at_ms: Optional[int] = Form(None),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    t0 = time.time()
    ext = Path(audio.filename).suffix if audio.filename else ".wav"
    if ext.lower() not in [".wav", ".mp3", ".m4a", ".aac", ".flac", ".ogg"]:
        raise HTTPException(status_code=400, detail="Unsupported audio format")

    user_dir = settings.storage_dir / f"user_{user.id}"
    user_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
    audio_path = user_dir / f"{ts}{ext}"
    audio_path.write_bytes(await audio.read())

    if preserve_fields:
        final_transcript = (transcript or "").strip()
        final_label = (emotion_label or "Neutral").strip() or "Neutral"
        if created_at_ms is not None:
            final_created_at = datetime.utcfromtimestamp(created_at_ms / 1000.0)
        else:
            final_created_at = datetime.utcnow()
        print(
            f"[UPLOAD] preserve_fields user={user.id} file={audio_path.name} "
            f"label={final_label} created_at={final_created_at.isoformat()}"
        )
    else:
        print(f"[UPLOAD] start user={user.id} file={audio_path.name}")
        final_transcript = asr_service.transcribe(audio_path)
        print(f"[UPLOAD] asr done in {time.time() - t0:.2f}s")
        probs = ser_service.infer(audio_path)
        final_label = ser_service.top_label(probs)
        final_created_at = datetime.utcnow()
        print(f"[UPLOAD] ser done in {time.time() - t0:.2f}s label={final_label}")

    diary = Diary(
        user_id=user.id,
        audio_path=str(audio_path),
        transcript=final_transcript,
        emotion_label=final_label,
        created_at=final_created_at,
    )
    db.add(diary)
    db.commit()
    db.refresh(diary)
    print(f"[UPLOAD] saved in {time.time() - t0:.2f}s id={diary.id}")
    return to_schema(diary)


@router.post("/asr", response_model=AsrResponse)
async def transcribe_audio(
    audio: UploadFile = File(...),
    user: User = Depends(get_current_user),
):
    ext = Path(audio.filename).suffix if audio.filename else ".wav"
    if ext.lower() not in [".wav", ".mp3", ".m4a", ".aac", ".flac", ".ogg"]:
        raise HTTPException(status_code=400, detail="Unsupported audio format")

    user_dir = settings.storage_dir / f"user_{user.id}"
    user_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
    audio_path = user_dir / f"asr_{ts}{ext}"
    audio_path.write_bytes(await audio.read())

    transcript = asr_service.transcribe(audio_path)
    return AsrResponse(transcript=transcript)


@router.get("", response_model=DiaryListResponse)
def list_diaries(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    items = (
        db.query(Diary)
        .filter(Diary.user_id == user.id)
        .order_by(Diary.created_at.desc())
        .all()
    )
    return DiaryListResponse(items=[to_schema(i) for i in items])


@router.get("/{diary_id}", response_model=DiaryItem)
def get_diary(diary_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    item = db.query(Diary).filter(Diary.id == diary_id, Diary.user_id == user.id).first()
    if not item:
        raise HTTPException(status_code=404, detail="Diary not found")
    return to_schema(item)


@router.get("/{diary_id}/audio")
def get_diary_audio(diary_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    item = db.query(Diary).filter(Diary.id == diary_id, Diary.user_id == user.id).first()
    if not item:
        raise HTTPException(status_code=404, detail="Diary not found")
    audio_path = Path(item.audio_path)
    if not audio_path.exists():
        raise HTTPException(status_code=404, detail="Audio file not found")
    return FileResponse(audio_path, media_type="audio/*", filename=audio_path.name)


@router.delete("/{diary_id}")
def delete_diary(diary_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    item = db.query(Diary).filter(Diary.id == diary_id, Diary.user_id == user.id).first()
    if not item:
        raise HTTPException(status_code=404, detail="Diary not found")

    audio_path = Path(item.audio_path)
    db.delete(item)
    db.commit()

    if audio_path.exists():
        audio_path.unlink(missing_ok=True)

    return {"status": "ok"}
