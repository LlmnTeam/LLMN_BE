# app/api/__init__.py
from fastapi import APIRouter
from app.api.summary import router as summary_router
from app.api.logs_question import router as logs_router
from app.api.auth import router as auth_router

router = APIRouter()

router.include_router(summary_router)
router.include_router(logs_router)
router.include_router(auth_router)