# app/services/api_key_service.py
import logging
from fastapi import HTTPException, status
from app.crud.openai_key_crud import find_key_by_user_id
from app.services.encryption_service import EncryptionService
from app.db.session import get_db_context

logger = logging.getLogger(__name__)
encryption_service = EncryptionService()

async def get_decrypted_api_key(user_id: int) -> str:
    try:
        async with get_db_context() as db:
            key_obj = await find_key_by_user_id(db, user_id)
            if not key_obj:
                logger.error(f"사용자 ID {user_id}에 대한 API 키를 찾을 수 없습니다.")
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="사용자 ID에 대한 API 키를 찾을 수 없습니다."
                )
            
            # 암호화된 키 복호화
            encrypted_key = key_obj.key_value
            try:
                decrypted_key = encryption_service.decrypt(encrypted_key)
                return decrypted_key
            except Exception as e:
                logger.error(f"API 키 복호화 실패: {str(e)}")
                raise HTTPException(
                    status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                    detail="API 키 복호화 중 오류가 발생했습니다."
                )
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="API 키 조회 중 오류가 발생했습니다."
        )