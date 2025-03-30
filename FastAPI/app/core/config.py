# app/core/config.py
import os
import logging
import redis
import tiktoken
from pydantic_settings import BaseSettings

LOGS_DIR = os.getenv("LOGS_DIR", "/project/logs")
ENCODING_NAME = "cl100k_base"

# 환경 설정
class Settings(BaseSettings):
    DATABASE_URL: str
    REDIS_HOST: str
    REDIS_PORT: int
    REDIS_DB: int
    ENCRYPTION_SECRET_KEY: str
    JWT_SECRET_KEY: str
    JWT_ALGORITHM: str 

    class Config:
        env_file = None  

settings = Settings()

# JWT 상수
SECRET_KEY = settings.JWT_SECRET_KEY
ALGORITHM = settings.JWT_ALGORITHM

# Tiktoken 인코딩 설정
encoding = tiktoken.get_encoding('cl100k_base')

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