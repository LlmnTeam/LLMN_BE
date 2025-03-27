# app/core/config.py
import os
import logging
import redis
import tiktoken
from pydantic_settings import BaseSettings

# 로그 디렉토리 경로
logs_dir = os.getenv("LOGS_DIR", "/project/logs")

# 환경 설정
class Settings(BaseSettings):
    DATABASE_URL: str
    REDIS_HOST: str
    REDIS_PORT: int
    REDIS_DB: int

    class Config:
        env_file = None  

settings = Settings()

# Redis 설정
r = redis.Redis(
    host=settings.REDIS_HOST,
    port=settings.REDIS_PORT,
    db=settings.REDIS_DB,
    decode_responses=True
)

# 로깅 설정
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Tiktoken 설정
encoding = tiktoken.get_encoding('cl100k_base')

# JWT 
SECRET_KEY = "MySecretKey"
ALGORITHM = "HS512"