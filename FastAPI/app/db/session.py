# app/db/session.py
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker
from app.core.config import settings
from contextlib import asynccontextmanager
import logging

logger = logging.getLogger(__name__)

engine = create_async_engine(
    settings.DATABASE_URL,
    future=True,
    echo=False,
    pool_size=10,                  # 기본 연결 풀 크기
    max_overflow=20,              # 최대 추가 연결 수
    pool_timeout=30,              # 연결 획득 타임아웃 (초)
    pool_recycle=1800,            # 연결 재활용 시간 (30분)
    pool_pre_ping=True,           # 연결 사용 전 상태 확인 (끊긴 연결 감지)
    connect_args={
        "connect_timeout": 10,    # 연결 시도 타임아웃
    }
)

AsyncSessionLocal = sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False               # 필요할 때만 명시적으로 flush 하도록 설정
)

@asynccontextmanager
async def get_db_context():
    session = AsyncSessionLocal()
    try:
        yield session
        await session.commit()
    except Exception as e:
        await session.rollback()
        raise
    finally:
        await session.close()  # 항상 세션 닫기