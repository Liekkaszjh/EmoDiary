from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
import sqlite3

from app.config import settings
from app.database import Base, engine
from app.routers.analytics import router as analytics_router
from app.routers.auth import router as auth_router
from app.routers.diaries import router as diaries_router
from app.routers.users import router as users_router
from app.services.asr_service import asr_service
from app.services.ser_service import ser_service


settings.storage_dir.mkdir(parents=True, exist_ok=True)
Base.metadata.create_all(bind=engine)


def _migrate_drop_emotion_probs_json_if_needed():
    db_file = settings.db_path
    if not db_file.exists():
        return
    conn = sqlite3.connect(str(db_file))
    try:
        cols = conn.execute("PRAGMA table_info(diaries)").fetchall()
        col_names = {row[1] for row in cols}
        if "emotion_probs_json" not in col_names:
            return

        conn.execute("BEGIN")
        conn.execute(
            """
            CREATE TABLE diaries_new (
                id INTEGER NOT NULL PRIMARY KEY,
                user_id INTEGER NOT NULL,
                audio_path VARCHAR(500) NOT NULL,
                transcript TEXT NOT NULL DEFAULT '',
                emotion_label VARCHAR(32) NOT NULL DEFAULT 'Neutral',
                created_at DATETIME NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users (id)
            )
            """
        )
        conn.execute(
            """
            INSERT INTO diaries_new (id, user_id, audio_path, transcript, emotion_label, created_at)
            SELECT id, user_id, audio_path, transcript, emotion_label, created_at
            FROM diaries
            """
        )
        conn.execute("DROP TABLE diaries")
        conn.execute("ALTER TABLE diaries_new RENAME TO diaries")
        conn.execute("CREATE INDEX IF NOT EXISTS ix_diaries_id ON diaries (id)")
        conn.execute("CREATE INDEX IF NOT EXISTS ix_diaries_user_id ON diaries (user_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS ix_diaries_created_at ON diaries (created_at)")
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


_migrate_drop_emotion_probs_json_if_needed()

app = FastAPI(title=settings.project_name)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth_router)
app.include_router(users_router)
app.include_router(diaries_router)
app.include_router(analytics_router)


@app.on_event("startup")
def warmup_models():
    # Warm up once at startup to avoid first-upload timeout in mobile clients.
    asr_service.warmup()
    ser_service.warmup()


@app.get("/health")
def health():
    return {"status": "ok"}
