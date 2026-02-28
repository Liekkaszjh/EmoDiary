from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.deps import get_current_user
from app.models import User
from app.schemas import UpdateProfileRequest, UserProfile


router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserProfile)
def get_me(user: User = Depends(get_current_user)):
    return UserProfile(
        id=user.id,
        username=user.username,
        nickname=user.nickname,
        avatar_url=user.avatar_url,
    )


@router.put("/me", response_model=UserProfile)
def update_me(
    payload: UpdateProfileRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user.nickname = payload.nickname
    user.avatar_url = payload.avatar_url
    db.commit()
    db.refresh(user)
    return UserProfile(
        id=user.id,
        username=user.username,
        nickname=user.nickname,
        avatar_url=user.avatar_url,
    )
