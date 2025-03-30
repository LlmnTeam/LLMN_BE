# app/core/conversation_config.py
from pydantic_settings import BaseSettings

class ConversationSettings(BaseSettings):
    # Redis 관련 설정
    REDIS_EXPIRY_SECONDS: int = 1800  # 30분
    
    # 대화 관리 설정
    DEFAULT_MAX_TOKEN_LENGTH: int = 70000
    DEFAULT_KEEP_RECENT_MESSAGES: int = 15
    
    # 메시지 형식 설정
    SUMMARY_PREFIX: str = "Previous Conversation Summary:\n"
    USER_MESSAGE_PREFIX: str = "User: "
    SYSTEM_MESSAGE_PREFIX: str = "System: "
    
    # 요약 생성 설정
    LLM_MODEL_NAME: str = "gpt-4o-mini"
    SUMMARIZATION_TEMPERATURE: float = 0.3
    SUMMARIZATION_MAX_TOKENS: int = 750

    # 토큰 간 스트리밍 지연 시간(초)
    STREAMING_DELAY: float = 0.1  
    
    class Config:
        env_prefix = "CONVERSATION_"

conversation_settings = ConversationSettings()