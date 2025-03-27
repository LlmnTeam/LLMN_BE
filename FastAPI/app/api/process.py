# app/api/process.py
import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from app.schemas.dto import (
    LogRequest, LogFilesRequest, ValidateAPIRequest
)
from app.services.generate_summaries import (
    generate_log_summary, generate_performance_summary, generate_daily_summary,
    generate_trend_summary, generate_recommend, generate_hourly_summary,
    validate_openai_api_key
)
from app.crud.openai_key import find_key_by_user_id
from app.services.conversation_manager import ConversationManager
from app.services.rag_service import Rag_Service
from app.utils.utils import combine_logs_and_question, get_user_id_from_token
from fastapi import APIRouter, Depends, HTTPException 
from app.db.session import get_db_context  

logger = logging.getLogger(__name__)
router = APIRouter()

@router.post("/process/logSummary")
async def process_log_summary(request: LogRequest):
    log_summary, is_urgent = await generate_log_summary(request.content, request.apiKey)
    
    return {
        "logSummary": log_summary,
        "isUrgent": is_urgent
    }

@router.post("/process/performanceSummary")
async def process_performance_summary(request: LogRequest):
    performance_summary = await generate_performance_summary(request.content, request.apiKey)
    
    return {
        "performanceSummary": performance_summary
    }

@router.post("/process/dailySummary")
async def process_daily_summary(request: LogRequest):
    daily_summary = await generate_daily_summary(request.content, request.apiKey)
    
    return {
        "dailySummary": daily_summary
    }

@router.post("/process/trendSummary")
async def process_trend_summary(request: LogRequest):
    trend_summary = await generate_trend_summary(request.content, request.apiKey)
    
    return {
        "trendSummary": trend_summary
    }

@router.post("/process/recommend")
async def process_recommend(request: LogRequest):
    recommend = await generate_recommend(request.content, request.apiKey)
    
    return {
        "recommend": recommend
    }

@router.post("/process/hourlySummary")
async def process_hourly_summary(request: LogRequest):
    hourly_summary = await generate_hourly_summary(request.content, request.apiKey)
    
    return {
        "hourlySummary": hourly_summary
    }

@router.post("/api/logs/question")
async def process_logs_and_question(
    request: LogFilesRequest,
    user_id: int = Depends(get_user_id_from_token)
):
    # 사용자 ID로 데이터베이스에서 API 키 조회
    async with get_db_context() as db:
        key_obj = await find_key_by_user_id(db, user_id)
        if not key_obj:
            raise HTTPException(status_code=404, detail="userId 찾기 실패")
        api_key = key_obj.key_value  # key_value 속성 추출

    conversation_manager = ConversationManager(user_id)

    # 로그와 질문을 포함한 최종 질문 생성
    final_question = combine_logs_and_question(request, conversation_manager)

    # RAG 서비스 초기화 (API 키 전달)
    rag_service = Rag_Service(api_key)

    # 스트리밍된 응답을 수집하기 위한 리스트
    response_collector = []

    async def stream_response():
        # OpenAI API가 토큰을 생성할 때마다, 응답을 수집하고 바로바로 chunk를 반환
        async for chunk in rag_service.generate_text_streaming(final_question):
            response_collector.append(chunk)  
            yield chunk

        # async 루프 완료(스트리밍이 종료) => 대화 히스토리에 저장
        complete_response = ''.join(response_collector)
        conversation_manager.add_to_history(request.question, complete_response)

    return StreamingResponse(stream_response(), media_type='text/event-stream')
    
@router.post("/validate-api-key")
async def validate_api_key(request: ValidateAPIRequest):
    is_valid = await validate_openai_api_key(request.apiKey)
    
    if is_valid:
        return {"isValid": True}
    else:
        return {"isValid": False}