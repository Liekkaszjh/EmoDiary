import json
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable, Optional
from urllib import error, request

from app.config import settings
from app.models import Diary


EMOTION_VALENCE = {
    "Sad": 1.0,
    "Fear": 2.0,
    "Angry": 2.0,
    "Neutral": 3.0,
    "Surprise": 4.0,
    "Happy": 5.0,
}

NEGATIVE_EMOTIONS = {"Sad", "Fear", "Angry"}
POSITIVE_EMOTIONS = {"Happy", "Surprise"}

RANGE_TEXT = {
    "day": "一天",
    "week": "一周",
    "month": "一个月",
    "today": "今天",
    "3d": "近三天",
    "7d": "近一周",
    "1m": "近一月",
    "6m": "近半年",
    "1y": "近一年",
}


@dataclass
class AgentResult:
    status: str
    message: str
    session_id: Optional[str] = None


def _truncate(text: str, limit: int = 90) -> str:
    cleaned = " ".join((text or "").strip().split())
    if len(cleaned) <= limit:
        return cleaned
    return f"{cleaned[:limit]}..."


def _build_context(diaries: Iterable[Diary], max_rows: int = 28) -> str:
    diary_list = list(diaries)
    if not diary_list:
        return "该时间段没有日记数据。"

    counts = {}
    values = []
    change_count = 0
    prev_emotion = None

    for item in diary_list:
        emotion = item.emotion_label or "Neutral"
        counts[emotion] = counts.get(emotion, 0) + 1
        values.append(EMOTION_VALENCE.get(emotion, 3.0))
        if prev_emotion is not None and prev_emotion != emotion:
            change_count += 1
        prev_emotion = emotion

    total = len(diary_list)
    dominant_emotion = max(counts.items(), key=lambda kv: kv[1])[0]
    dominant_ratio = counts[dominant_emotion] / total
    negative_ratio = sum(v for k, v in counts.items() if k in NEGATIVE_EMOTIONS) / total
    positive_ratio = sum(v for k, v in counts.items() if k in POSITIVE_EMOTIONS) / total
    volatility = change_count / max(1, total - 1)
    average_valence = sum(values) / len(values)

    count_text = ", ".join(
        f"{name}:{cnt}({cnt / total:.0%})"
        for name, cnt in sorted(counts.items(), key=lambda kv: kv[1], reverse=True)
    )

    rows = []
    for item in diary_list[-max_rows:]:
        ts = item.created_at.strftime("%Y-%m-%d %H:%M")
        rows.append(f"- {ts} | emotion={item.emotion_label} | text={_truncate(item.transcript)}")

    header = (
        f"总记录数: {total}\n"
        f"主导情绪: {dominant_emotion} ({dominant_ratio:.0%})\n"
        f"负向情绪占比: {negative_ratio:.0%}\n"
        f"正向情绪占比: {positive_ratio:.0%}\n"
        f"波动指数(相邻记录情绪变化率): {volatility:.0%}\n"
        f"平均情绪分值(1低-5高): {average_valence:.2f}\n"
        f"情绪分布: {count_text}\n"
        f"记录样本(最近{min(total, max_rows)}条):"
    )
    return f"{header}\n" + "\n".join(rows)


def _missing_config_message() -> str:
    missing = []
    if not settings.dashscope_api_key:
        missing.append("DASHSCOPE_API_KEY")
    if (not settings.dashscope_app_id) or settings.dashscope_app_id == "YOUR_DASHSCOPE_APP_ID":
        missing.append("DASHSCOPE_APP_ID")
    missing_text = ", ".join(missing) if missing else "Unknown"
    return f"Agent 未配置完成，请先设置环境变量: {missing_text}"


def _call_dashscope(prompt: str, session_id: Optional[str] = None) -> AgentResult:
    if (
        (not settings.dashscope_api_key)
        or (not settings.dashscope_app_id)
        or settings.dashscope_app_id == "YOUR_DASHSCOPE_APP_ID"
    ):
        return AgentResult(status="config_error", message=_missing_config_message())

    payload = {
        "input": {
            "prompt": prompt,
        },
        "parameters": {
            "incremental_output": False,
        },
        "debug": {},
    }
    if session_id:
        payload["input"]["session_id"] = session_id

    url = f"{settings.dashscope_endpoint}/api/v1/apps/{settings.dashscope_app_id}/completion"
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        url=url,
        data=data,
        method="POST",
        headers={
            "Authorization": f"Bearer {settings.dashscope_api_key}",
            "Content-Type": "application/json",
            "X-DashScope-SSE": "disable",
        },
    )

    try:
        with request.urlopen(req, timeout=settings.dashscope_timeout_seconds) as resp:
            raw = resp.read().decode("utf-8")
    except error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8")
        except Exception:
            detail = str(exc)
        return AgentResult(status="http_error", message=f"Agent 请求失败: HTTP {exc.code}. {detail}")
    except Exception as exc:
        return AgentResult(status="network_error", message=f"Agent 网络错误: {exc}")

    try:
        data_obj = json.loads(raw)
    except json.JSONDecodeError:
        return AgentResult(status="parse_error", message=f"Agent 返回非 JSON 内容: {raw[:200]}")

    output = data_obj.get("output") or {}
    text = (output.get("text") or "").strip()
    returned_session_id = output.get("session_id")
    if not text:
        text = "Agent 未返回可用文本，请检查应用编排与模型输出。"
    return AgentResult(status="ok", message=text, session_id=returned_session_id)


def generate_period_report(range_key: str, diaries: Iterable[Diary]) -> AgentResult:
    diary_list = list(diaries)
    if not diary_list:
        return AgentResult(
            status="empty",
            message="当前时间段还没有记录，无法生成周期总结。先记录几条语音日记再来分析会更准确。",
        )

    range_text = RANGE_TEXT.get(range_key, range_key)
    context = _build_context(diary_list)
    now_text = datetime.utcnow().strftime("%Y-%m-%d %H:%M UTC")

    prompt = f"""
你是“情绪洞察分析师”。请基于用户在{range_text}内的语音日记情绪数据，生成结构化总结报告。
输出要求:
1) 使用中文。
2) 仅输出以下四个标题，不要输出多余前言: 【阶段总结】【情绪波动与触发点】【可执行建议】【下一阶段观察重点】。
3) 总字数控制在 320-520 个中文字符。
4) 【可执行建议】给出 3-5 条，且每条以“- ”开头，必须具体可执行。
5) 不做医疗诊断；如果数据不足，明确说明不确定性。
分析时间: {now_text}
统计区间: {range_text}
数据如下:
{context}
""".strip()

    return _call_dashscope(prompt=prompt, session_id=None)


def chat_with_emotion_coach(
    range_key: str,
    user_message: str,
    diaries: Iterable[Diary],
    session_id: Optional[str] = None,
) -> AgentResult:
    text = (user_message or "").strip()
    if not text:
        return AgentResult(status="bad_request", message="消息不能为空。")

    range_text = RANGE_TEXT.get(range_key, range_key)
    context = _build_context(diaries)

    prompt = f"""
你是“情绪管理助手”，目标是帮助用户进行日常情绪调节。
回复规则:
1) 使用中文，语气温和、具体、不过度说教。
2) 总字数控制在 120-220 个中文字符。
3) 先用 1 句共情（不超过 40 字），再给 2-3 条可执行建议，每条以“- ”开头。
4) 不做医疗诊断；如出现明显自伤/伤人风险，提醒尽快联系身边可信任的人或当地紧急援助渠道。
当前对话所参考的统计区间: {range_text}
用户该区间历史数据摘要:
{context}

用户当前消息: {text}
""".strip()

    return _call_dashscope(prompt=prompt, session_id=session_id)
