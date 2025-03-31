# app/core/log_analysis_config.py
from pydantic_settings import BaseSettings

class LogAnalysisSettings(BaseSettings):
    # LLM 모델 설정
    DEFAULT_MODEL: str = "gpt-4o-mini"
    DEFAULT_TEMPERATURE: float = 0.3
    
    # 토큰 제한 설정
    API_VALIDATION_TOKENS: int = 10
    LOG_SUMMARY_TOKENS: int = 1200
    PERFORMANCE_SUMMARY_TOKENS: int = 500
    DAILY_SUMMARY_TOKENS: int = 1200
    TREND_SUMMARY_TOKENS: int = 2000
    RECOMMENDATION_TOKENS: int = 750
    HOURLY_SUMMARY_TOKENS: int = 750
    
    # 긴급 체크 관련 설정
    URGENCY_CHECK_MARKER: str = "🔍 [긴급 여부 체크]"
    
    class Config:
        env_prefix = "LOG_ANALYSIS_"

log_analysis_settings = LogAnalysisSettings()