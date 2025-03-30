# app/api/api_key_routes.py
import logging
from fastapi import APIRouter
from app.models import ValidateAPIRequest
from app.services.log_analysis_service import validate_openai_api_key

logger = logging.getLogger(__name__)
router = APIRouter(tags=["authentication"])

@router.post("/validate-api-key")
async def validate_api_key(request: ValidateAPIRequest):
    is_valid = await validate_openai_api_key(request.apiKey)
    
    if is_valid:
        return {"isValid": True}
    else:
        return {"isValid": False}