# app/services/encryption_service.py
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad
import base64
import logging
from app.core.config import settings

logger = logging.getLogger(__name__)

class EncryptionService:
    def __init__(self):
        self.secret_key = self._prepare_key(settings.ENCRYPTION_SECRET_KEY)
    
    def _prepare_key(self, key):
        key_bytes = key.encode('utf-8')
        if len(key_bytes) not in [16, 24, 32]:
            if len(key_bytes) > 32:
                return key_bytes[:32]
            else:
                return key_bytes.ljust(32, b'\0')
        return key_bytes
    
    def decrypt(self, encrypted_data):
        try:
            encrypted_bytes = base64.b64decode(encrypted_data)
            
            cipher = AES.new(self.secret_key, AES.MODE_ECB)
            decrypted_bytes = cipher.decrypt(encrypted_bytes)
            
            # PKCS5/7 패딩 제거
            unpadded = unpad(decrypted_bytes, AES.block_size)
            
            return unpadded.decode('utf-8')
        except Exception as e:
            logger.error(f"API 키 복호화 오류: {str(e)}")
            raise Exception(f"복호화 오류: {str(e)}")