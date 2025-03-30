# app/api/log_analysis_routes.py
import logging
from fastapi import APIRouter
from app.models import LogRequest
from app.services.log_analysis_service import (
    generate_log_summary, generate_performance_summary,
    generate_daily_summary, generate_trend_summary, generate_hourly_summary,
    generate_recommend
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/process", tags=["summaries"])

@router.post("/logSummary")
async def process_log_summary(request: LogRequest):
    log_summary, is_urgent = await generate_log_summary(request.content, request.apiKey)
    
    return {
        "logSummary": log_summary,
        "isUrgent": is_urgent
    }

@router.post("/performanceSummary")
async def process_performance_summary(request: LogRequest):
    performance_summary = await generate_performance_summary(request.content, request.apiKey)
    
    return {
        "performanceSummary": performance_summary
    }

@router.post("/dailySummary")
async def process_daily_summary(request: LogRequest):
    daily_summary = await generate_daily_summary(request.content, request.apiKey)
    
    return {
        "dailySummary": daily_summary
    }

@router.post("/trendSummary")
async def process_trend_summary(request: LogRequest):
    trend_summary = await generate_trend_summary(request.content, request.apiKey)
    
    return {
        "trendSummary": trend_summary
    }

@router.post("/hourlySummary")
async def process_hourly_summary(request: LogRequest):
    hourly_summary = await generate_hourly_summary(request.content, request.apiKey)
    
    return {
        "hourlySummary": hourly_summary
    }

@router.post("/recommend")
async def process_recommend(request: LogRequest):
    recommend = await generate_recommend(request.content, request.apiKey)
    
    return {
        "recommend": recommend
    }