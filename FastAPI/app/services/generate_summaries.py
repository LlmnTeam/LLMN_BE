# app/services/generate_summaries.py
import logging

from langchain_openai import ChatOpenAI
from langchain.prompts import PromptTemplate
from app.services.prompt_templates import (
    LOG_SUMMARY_PROMPT, 
    PERFORMANCE_SUMMARY_PROMPT, 
    DAILY_SUMMARY_PROMPT, 
    TREND_SUMMARY_PROMPT, 
    RECOMMENDATION_PROMPT, 
    HOURLY_SUMMARY_PROMPT
)

logger = logging.getLogger(__name__)

# 상수 정의
DEFAULT_MODEL = "gpt-4o-mini"
DEFAULT_TEMPERATURE = 0.3
API_VALIDATION_TOKENS = 10
LOG_SUMMARY_TOKENS = 1200
PERFORMANCE_SUMMARY_TOKENS = 500
DAILY_SUMMARY_TOKENS = 1200
TREND_SUMMARY_TOKENS = 2000
RECOMMENDATION_TOKENS = 750
HOURLY_SUMMARY_TOKENS = 750

# 긴급 체크 관련 상수
URGENCY_CHECK_MARKER = "🔍 [긴급 여부 체크]"

async def _invoke_llm(
    prompt: str, 
    api_key: str, 
    max_tokens: int = 1000, 
    temperature: float = DEFAULT_TEMPERATURE,
    model: str = DEFAULT_MODEL
) -> str:
    try:
        # LLM 초기화
        chatmodel = ChatOpenAI(
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
            openai_api_key=api_key
        )
        
        # 프롬프트 템플릿 생성 및 포맷팅
        prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
        formatted_prompt = prompt_template.format(prompt=prompt)
        
        # LLM 호출
        response = chatmodel.invoke(formatted_prompt)
        return response.content
    except Exception as e:
        logger.error(f"LLM 호출 중 오류 발생: {str(e)}")
        raise

async def validate_openai_api_key(api_key: str) -> bool:
    try:
        prompt = "This is a test prompt to validate the OpenAI API key. You can respond with just 'Hi'."

        await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=API_VALIDATION_TOKENS
        )
        return True
    except Exception as e:
        logger.warning(f"API 키 검증 실패: {str(e)}")
        return False

async def generate_log_summary(content: str, api_key: str):     
    try:
        prompt = LOG_SUMMARY_PROMPT.replace("{content}", content)
        
        response_text = await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=LOG_SUMMARY_TOKENS
        )
        
        # 긴급 여부 체크 섹션 찾기
        urgency_start_index = response_text.find(URGENCY_CHECK_MARKER)
        
        # 로그 요약과 긴급 여부 분리
        if urgency_start_index != -1:
            log_summary = response_text[:urgency_start_index].strip()
            is_urgent_line = response_text[urgency_start_index:].split('\n')[-1].strip()
            is_urgent = "true" in is_urgent_line.lower()
        else:
            log_summary = response_text.strip()
            is_urgent = False
        
        return log_summary, is_urgent
    except Exception as e:
        return f"로그 요약을 생성할 수 없습니다: {str(e)}", False

async def generate_performance_summary(content: str, api_key: str) -> str:
    try:
        prompt = PERFORMANCE_SUMMARY_PROMPT.replace("{content}", content)
        
        return await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=PERFORMANCE_SUMMARY_TOKENS
        )
    except Exception as e:
        return f"성능 요약을 생성할 수 없습니다: {str(e)}"

async def generate_daily_summary(content: str, api_key: str) -> str:
    try:
        prompt = DAILY_SUMMARY_PROMPT.replace("{content}", content)
        
        return await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=DAILY_SUMMARY_TOKENS
        )
    except Exception as e:
        return f"일일 요약을 생성할 수 없습니다: {str(e)}"

async def generate_trend_summary(content: str, api_key: str) -> str:
    try:
        prompt = TREND_SUMMARY_PROMPT.replace("{content}", content)
        
        return await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=TREND_SUMMARY_TOKENS
        )
    except Exception as e:
        return f"트렌드 요약을 생성할 수 없습니다: {str(e)}"

async def generate_recommend(content: str, api_key: str) -> str:
    try:
        prompt = RECOMMENDATION_PROMPT.replace("{content}", content)
        
        return await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=RECOMMENDATION_TOKENS
        )
    except Exception as e:
        return f"권장 사항을 생성할 수 없습니다: {str(e)}"

async def generate_hourly_summary(content: str, api_key: str) -> str:
    try:
        prompt = HOURLY_SUMMARY_PROMPT.replace("{content}", content)
        
        return await _invoke_llm(
            prompt=prompt,
            api_key=api_key,
            max_tokens=HOURLY_SUMMARY_TOKENS
        )
    except Exception as e:
        return f"시간별 요약을 생성할 수 없습니다: {str(e)}"