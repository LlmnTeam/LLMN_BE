# app/services/log_question_service.py
import logging
import os

from typing import List, AsyncGenerator
from fastapi import HTTPException
from app.models import LogFilesRequest
from app.services.conversation_manager import ConversationManager
from app.services.rag_service import RagService
from app.core.config import LOGS_DIR
from app.services.prompt_templates import (
    LOG_QUESTION_PERSONA,
    LOG_FILES_SECTION_HEADER,
    CONVERSATION_HISTORY_HEADER,
    USER_QUESTION_HEADER
)

logger = logging.getLogger(__name__)

# 질문과 로그 파일을 조합해 LLM 프롬프트 생성
def prepare_question(request: LogFilesRequest, conversation_manager: ConversationManager) -> str:
    log_message_builder = []

    # 첫 질문 시 이전 대화 초기화
    if request.isFirstQuestion:
        conversation_manager.clear_all_messages()

    prompt = ""

    # 첫 질문에만 로그 파일 내용 포함
    if request.isFirstQuestion:
        prompt = LOG_QUESTION_PERSONA

        # 요청된 모든 로그 파일 내용 추가
        for logFile in request.logFiles:
            file_path = os.path.join(LOGS_DIR, logFile.name)
            log_content = _read_log_file(file_path)
            log_message_builder.append(f"### Log file: {logFile.name} ###\n{log_content}\n")

        prompt += f"{LOG_FILES_SECTION_HEADER}{''.join(log_message_builder)}"

    # 이전 대화 컨텍스트와 현재 질문 추가
    prompt += (
        f"{CONVERSATION_HISTORY_HEADER}"
        f"{conversation_manager.get_formatted_conversation()}"
        f"{USER_QUESTION_HEADER}"
        f"{request.question}\n"
    )

    return prompt

# LLM 응답을 스트리밍 방식으로 생성하고 대화 히스토리에 저장
async def generate_streaming_response(
    rag_service: RagService,
    question: str,
    conversation_manager: ConversationManager,
    original_question: str,
    api_key: str
) -> AsyncGenerator[str, None]:
    
    response_chunks: List[str] = []
    
    try:
        # 응답을 실시간 스트리밍으로 전달
        async for chunk in rag_service.generate_text_streaming(question):
            response_chunks.append(chunk)
            yield chunk
        
        # 완성된 응답을 대화 컨텍스트에 저장
        complete_response = ''.join(response_chunks)
        conversation_manager.add_messages(original_question, complete_response, api_key)
    except Exception as e:
        error_message = f"응답 생성 중 오류가 발생했습니다: {str(e)}"
        yield error_message

def _read_log_file(file_path: str):
    try:
        with open(file_path, "r", encoding="utf-8") as file:
            return file.read()
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"파일을 찾을 수 없습니다: {file_path}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))