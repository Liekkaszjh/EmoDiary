from datetime import datetime
from typing import Dict, List, Optional

from pydantic import BaseModel


class RegisterRequest(BaseModel):
    username: str
    password: str
    nickname: str = "User"


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


class UserProfile(BaseModel):
    id: int
    username: str
    nickname: str
    avatar_url: str


class UpdateProfileRequest(BaseModel):
    nickname: str
    avatar_url: str = ""


class DiaryItem(BaseModel):
    id: int
    created_at: datetime
    transcript: str
    emotion_label: str
    audio_path: str


class DiaryListResponse(BaseModel):
    items: List[DiaryItem]


class AsrResponse(BaseModel):
    transcript: str


class InsightResponse(BaseModel):
    range_key: str
    text: str
    trend: List[Dict[str, str]]
    pie: Dict[str, float]
    calendar: List[Dict[str, str]]


class AgentSummaryRequest(BaseModel):
    range_key: str
    tz_offset_minutes: Optional[int] = 0


class AgentSummaryResponse(BaseModel):
    range_key: str
    status: str
    message: str
    session_id: Optional[str] = None
    saved_report_id: Optional[int] = None
    saved_at: Optional[datetime] = None


class AgentReportItem(BaseModel):
    id: int
    range_key: str
    period_start: datetime
    period_end: datetime
    source_diary_count: int
    summary_text: str
    created_at: datetime


class AgentReportListResponse(BaseModel):
    items: List[AgentReportItem]


class AgentChatRequest(BaseModel):
    message: str
    range_key: str = "7d"
    session_id: Optional[str] = None
    tz_offset_minutes: Optional[int] = 0


class AgentChatResponse(BaseModel):
    range_key: str
    status: str
    message: str
    session_id: Optional[str] = None
