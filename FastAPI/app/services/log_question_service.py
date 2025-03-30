# app/services/log_question_service.py
import logging
import os

from typing import List, AsyncGenerator
from fastapi import HTTPException, status
from app.models import LogFilesRequest
from app.crud.openai_key import find_key_by_user_id
from app.services.conversation_manager import ConversationManager
from app.services.rag_service import RagService
from app.db.session import get_db_context
from app.core.config import logs_dir
from app.utils.utils import read_log_file
from app.services.prompt_templates import (
    LOG_QUESTION_PERSONA,
    LOG_FILES_SECTION_HEADER,
    CONVERSATION_HISTORY_HEADER,
    USER_QUESTION_HEADER
)

logger = logging.getLogger(__name__)

async def get_api_key_for_user(user_id: int) -> str:
    try:
        async with get_db_context() as db:
            key_obj = await find_key_by_user_id(db, user_id)
            if not key_obj:
                logger.error(f"사용자 ID {user_id}에 대한 API 키를 찾을 수 없습니다.")
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="사용자 ID에 대한 API 키를 찾을 수 없습니다."
                )
            return key_obj.key_value
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="API 키 조회 중 오류가 발생했습니다."
        )

def prepare_question(request: LogFilesRequest, conversation_manager: ConversationManager) -> str:
    log_message_builder = []

    # 첫 번째 질문일 경우 대화 히스토리와 요약 삭제
    if request.isFirstQuestion:
        conversation_manager.clear_all_messages()

    prompt = ""

    # 첫 번째 질문일 경우에만 로그 파일의 내용을 추가
    if request.isFirstQuestion:
        prompt = LOG_QUESTION_PERSONA

        # 로그 파일의 내용을 추가
        for logFile in request.logFiles:
            file_path = os.path.join(logs_dir, logFile.name)
            log_content = read_log_file(file_path)
            log_message_builder.append(f"### Log file: {logFile.name} ###\n{log_content}\n")

        prompt += f"{LOG_FILES_SECTION_HEADER}{''.join(log_message_builder)}"

    # 대화 히스토리 및 질문 추가
    prompt += (
        f"{CONVERSATION_HISTORY_HEADER}"
        f"{conversation_manager.get_formatted_conversation()}"
        f"{USER_QUESTION_HEADER}"
        f"{request.question}\n"
    )

    return prompt

async def generate_streaming_response(
    rag_service: RagService,
    question: str,
    conversation_manager: ConversationManager,
    original_question: str,
    api_key: str
) -> AsyncGenerator[str, None]:
    
    response_chunks: List[str] = []
    
    try:
        # 응답 스트리밍 및 수집
        async for chunk in rag_service.generate_text_streaming(question):
            response_chunks.append(chunk)
            yield chunk
        
        # 완료된 응답을 대화 히스토리에 저장
        complete_response = ''.join(response_chunks)
        conversation_manager.add_messages(original_question, complete_response, api_key)
    except Exception as e:
        error_message = f"응답 생성 중 오류가 발생했습니다: {str(e)}"
        yield error_message