# app/crud/openai_key.py
import logging
from sqlalchemy.future import select
from app.models.openai_key import OpenAIKey
from app.utils.retry import with_db_retry

logger = logging.getLogger(__name__)

@with_db_retry(max_retries=3)
async def find_key_by_user_id(db, user_id: int):
    result = await db.execute(
        select(OpenAIKey).filter(OpenAIKey.user_id == user_id)
    )
    return result.scalars().first()