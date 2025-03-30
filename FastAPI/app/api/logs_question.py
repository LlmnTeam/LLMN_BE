# app/api/logs_routes.py
import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from app.models import LogFilesRequest
from app.services.conversation_manager import ConversationManager
from app.services.rag_service import RagService
from app.services.log_question_service import (
    get_api_key_for_user,
    prepare_question,
    generate_streaming_response
)
from app.utils.utils import get_user_id_from_token

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api", tags=["logs"])

@router.post("/logs/question")
async def process_logs_and_question(
    request: LogFilesRequest,
    user_id: int = Depends(get_user_id_from_token)
) -> StreamingResponse:
    
    api_key = await get_api_key_for_user(user_id)
    
    conversation_manager = ConversationManager(user_id)
    final_question = prepare_question(request, conversation_manager)
    rag_service = RagService(api_key)
    
    return StreamingResponse(
        generate_streaming_response(
            rag_service=rag_service,
            question=final_question,
            conversation_manager=conversation_manager,
            original_question=request.question,
            api_key=api_key
        ),
        media_type='text/event-stream'
    )