from collections import defaultdict
from datetime import datetime, time, timedelta
from typing import Dict, List, Optional, Tuple

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.database import get_db
from app.deps import get_current_user
from app.models import AgentReport, Diary, User
from app.schemas import (
    AgentChatRequest,
    AgentChatResponse,
    AgentReportItem,
    AgentReportListResponse,
    AgentSummaryRequest,
    AgentSummaryResponse,
    InsightResponse,
)
from app.services.agent_service import chat_with_emotion_coach, generate_period_report


router = APIRouter(prefix="/analytics", tags=["analytics"])


INSIGHT_RANGE_DAYS = {
    "today": 1,
    "3d": 3,
    "7d": 7,
    "1m": 30,
    "6m": 180,
    "1y": 365,
}

INSIGHT_RANGE_ALIASES = {
    "today": "today",
    "day": "today",
    "1d": "today",
    "3d": "3d",
    "7d": "7d",
    "week": "7d",
    "1m": "1m",
    "month": "1m",
    "30d": "1m",
    "6m": "6m",
    "180d": "6m",
    "1y": "1y",
    "year": "1y",
    "365d": "1y",
}

REPORT_RANGE_ALIASES = {
    "day": "day",
    "today": "day",
    "1d": "day",
    "week": "week",
    "7d": "week",
    "month": "month",
    "1m": "month",
    "30d": "month",
}

REPORT_RANGE_DAYS = {
    "day": 1,
    "week": 7,
    "month": 30,
}


def normalize_insight_range(range_key: str) -> str:
    key = (range_key or "").strip().lower()
    return INSIGHT_RANGE_ALIASES.get(key, "7d")


def normalize_report_range(range_key: str) -> str:
    key = (range_key or "").strip().lower()
    return REPORT_RANGE_ALIASES.get(key, "week")


def _normalize_tz_offset_minutes(tz_offset_minutes: Optional[int]) -> int:
    if tz_offset_minutes is None:
        return 0
    # Clamp to plausible timezone offsets [-14h, +14h].
    return max(-840, min(840, int(tz_offset_minutes)))


def _range_window_utc(days: int, tz_offset_minutes: Optional[int]) -> Tuple[datetime, datetime]:
    offset_minutes = _normalize_tz_offset_minutes(tz_offset_minutes)
    now_utc = datetime.utcnow()
    now_local = now_utc + timedelta(minutes=offset_minutes)
    start_local_date = now_local.date() - timedelta(days=days - 1)
    start_local = datetime.combine(start_local_date, time.min)
    start_utc = start_local - timedelta(minutes=offset_minutes)
    return start_utc, now_utc


def query_user_diaries(
    db: Session,
    user_id: int,
    days: int,
    tz_offset_minutes: Optional[int] = 0,
) -> List[Diary]:
    start_time, end_time = _range_window_utc(days=days, tz_offset_minutes=tz_offset_minutes)
    return (
        db.query(Diary)
        .filter(
            Diary.user_id == user_id,
            Diary.created_at >= start_time,
            Diary.created_at <= end_time,
        )
        .order_by(Diary.created_at.asc())
        .all()
    )


def query_user_diaries_between(
    db: Session,
    user_id: int,
    period_start: datetime,
    period_end: datetime,
) -> List[Diary]:
    return (
        db.query(Diary)
        .filter(
            Diary.user_id == user_id,
            Diary.created_at >= period_start,
            Diary.created_at <= period_end,
        )
        .order_by(Diary.created_at.asc())
        .all()
    )


def to_report_item_schema(item: AgentReport) -> AgentReportItem:
    return AgentReportItem(
        id=item.id,
        range_key=item.range_key,
        period_start=item.period_start,
        period_end=item.period_end,
        source_diary_count=item.source_diary_count,
        summary_text=item.summary_text,
        created_at=item.created_at,
    )


def _daily_main_emotion(diaries: List[Diary]) -> Dict[str, str]:
    grouped: Dict[str, List[Diary]] = defaultdict(list)
    for item in diaries:
        day_key = item.created_at.strftime("%Y-%m-%d")
        grouped[day_key].append(item)

    out: Dict[str, str] = {}
    for day_key, day_items in grouped.items():
        count = defaultdict(int)
        for item in day_items:
            count[item.emotion_label] += 1
        max_count = max(count.values())
        candidates = {k for k, v in count.items() if v == max_count}

        if len(candidates) == 1:
            out[day_key] = next(iter(candidates))
            continue

        latest_candidate = max(
            (item for item in day_items if item.emotion_label in candidates),
            key=lambda item: item.created_at,
        )
        out[day_key] = latest_candidate.emotion_label
    return out


def _build_text_summary(diaries: List[Diary], days: int, emotion_count: Dict[str, int]) -> str:
    total = len(diaries)
    if total == 0:
        return "当前时间段没有日记记录。"

    top_emotion, top_count = max(emotion_count.items(), key=lambda kv: kv[1])
    top_ratio = top_count / total

    positive = emotion_count.get("Happy", 0) + emotion_count.get("Surprise", 0)
    negative = (
        emotion_count.get("Sad", 0)
        + emotion_count.get("Angry", 0)
        + emotion_count.get("Fear", 0)
    )
    neutral = emotion_count.get("Neutral", 0)

    changes = 0
    for idx in range(1, total):
        if diaries[idx].emotion_label != diaries[idx - 1].emotion_label:
            changes += 1
    volatility = changes / max(1, total - 1)

    if volatility >= 0.55:
        volatility_text = "情绪波动较明显"
    elif volatility >= 0.30:
        volatility_text = "情绪存在一定波动"
    else:
        volatility_text = "情绪整体较稳定"

    return (
        f"最近{days}天共记录{total}条，主导情绪为 {top_emotion}（{top_ratio:.0%}）。"
        f"正向情绪占比 {positive / total:.0%}，中性占比 {neutral / total:.0%}，负向占比 {negative / total:.0%}。"
        f"{volatility_text}，建议持续记录并关注触发情绪变化的事件。"
    )


@router.get("/insight", response_model=InsightResponse)
def get_insight(
    range_key: str = "today",
    tz_offset_minutes: int = Query(default=0, ge=-840, le=840),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    normalized_range = normalize_insight_range(range_key)
    days = INSIGHT_RANGE_DAYS[normalized_range]
    diaries = query_user_diaries(
        db=db,
        user_id=user.id,
        days=days,
        tz_offset_minutes=tz_offset_minutes,
    )

    if not diaries:
        return InsightResponse(
            range_key=normalized_range,
            text="当前时间段没有日记记录。",
            trend=[],
            pie={},
            calendar=[],
        )

    emotion_count: Dict[str, int] = defaultdict(int)
    for item in diaries:
        emotion_count[item.emotion_label] += 1

    total = float(len(diaries))
    pie = {k: v / total for k, v in emotion_count.items()}

    trend = [
        {"date": item.created_at.strftime("%m-%d"), "emotion": item.emotion_label}
        for item in diaries
    ]
    day_main_emotion = _daily_main_emotion(diaries)
    calendar = [
        {"date": day_key, "emotion": emotion}
        for day_key, emotion in sorted(day_main_emotion.items())
    ]
    text = _build_text_summary(diaries=diaries, days=days, emotion_count=emotion_count)

    return InsightResponse(
        range_key=normalized_range,
        text=text,
        trend=trend,
        pie=pie,
        calendar=calendar,
    )


def _load_report_context(
    payload_range_key: str,
    tz_offset_minutes: Optional[int],
    db: Session,
    user_id: int,
) -> Tuple[str, datetime, datetime, List[Diary]]:
    normalized_range = normalize_report_range(payload_range_key)
    days = REPORT_RANGE_DAYS[normalized_range]
    period_start, period_end = _range_window_utc(
        days=days,
        tz_offset_minutes=tz_offset_minutes,
    )
    diaries = query_user_diaries_between(
        db=db,
        user_id=user_id,
        period_start=period_start,
        period_end=period_end,
    )
    return normalized_range, period_start, period_end, diaries


@router.post("/agent-report", response_model=AgentSummaryResponse)
def generate_agent_report(
    payload: AgentSummaryRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    normalized_range, period_start, period_end, diaries = _load_report_context(
        payload.range_key,
        payload.tz_offset_minutes,
        db,
        user.id,
    )
    result = generate_period_report(range_key=normalized_range, diaries=diaries)
    saved_report_id: Optional[int] = None
    saved_at: Optional[datetime] = None
    if result.status == "ok":
        report = AgentReport(
            user_id=user.id,
            range_key=normalized_range,
            period_start=period_start,
            period_end=period_end,
            source_diary_count=len(diaries),
            summary_text=result.message,
            created_at=datetime.utcnow(),
        )
        db.add(report)
        db.commit()
        db.refresh(report)
        saved_report_id = report.id
        saved_at = report.created_at

    return AgentSummaryResponse(
        range_key=normalized_range,
        status=result.status,
        message=result.message,
        session_id=result.session_id,
        saved_report_id=saved_report_id,
        saved_at=saved_at,
    )


@router.get("/agent-reports", response_model=AgentReportListResponse)
def list_agent_reports(
    range_key: str = Query(default="", description="day|week|month"),
    limit: int = Query(default=20, ge=1, le=100),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    normalized_range = (range_key or "").strip().lower()
    query = db.query(AgentReport).filter(AgentReport.user_id == user.id)
    if normalized_range and normalized_range in REPORT_RANGE_ALIASES:
        query = query.filter(
            AgentReport.range_key == normalize_report_range(normalized_range)
        )
    rows = query.order_by(AgentReport.created_at.desc()).limit(limit).all()
    return AgentReportListResponse(items=[to_report_item_schema(item) for item in rows])


@router.post("/agent-chat", response_model=AgentChatResponse)
def agent_chat(
    payload: AgentChatRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    normalized_insight_range = normalize_insight_range(payload.range_key)
    days = INSIGHT_RANGE_DAYS[normalized_insight_range]
    diaries = query_user_diaries(
        db=db,
        user_id=user.id,
        days=days,
        tz_offset_minutes=payload.tz_offset_minutes,
    )

    result = chat_with_emotion_coach(
        range_key=normalized_insight_range,
        user_message=payload.message,
        diaries=diaries,
        session_id=payload.session_id,
    )
    return AgentChatResponse(
        range_key=normalized_insight_range,
        status=result.status,
        message=result.message,
        session_id=result.session_id,
    )
