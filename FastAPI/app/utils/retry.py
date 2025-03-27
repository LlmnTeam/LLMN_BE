# app/utils/retry.py
import asyncio
import logging
from functools import wraps
from sqlalchemy.exc import SQLAlchemyError, OperationalError, DatabaseError, IntegrityError
from pymysql.err import OperationalError as PyMySQLOperationalError

logger = logging.getLogger(__name__)

def with_db_retry(max_retries=3, retry_delay=1.0, backoff_factor=2.0):
    def decorator(func):
        @wraps(func)
        async def wrapper(*args, **kwargs):
            last_exception = None
            current_delay = retry_delay
            
            for attempt in range(max_retries + 1):  # 원래 시도 + 재시도
                try:
                    return await func(*args, **kwargs)
                except (SQLAlchemyError, PyMySQLOperationalError) as e:
                    last_exception = e
                    
                    # 재시도 가능한 오류인지 더 세분화하여 확인
                    is_retryable = (
                        isinstance(e, OperationalError) or
                        (isinstance(e, DatabaseError) and not isinstance(e, IntegrityError)) or
                        any(pattern in str(e).lower() for pattern in 
                            ["connection", "timeout", "reset by peer", "deadlock", "lock wait"])
                    )
                    
                    # 마지막 시도이거나 재시도할 필요가 없는 오류인 경우
                    if attempt >= max_retries or not is_retryable:
                        logger.error(f"Database operation failed after {attempt+1} attempts: {str(e)}")
                        raise last_exception
                    
                    await asyncio.sleep(current_delay)
                    current_delay *= backoff_factor  # 대기 시간 증가
            
            # 이 코드는 실행되지 않아야 하지만, 안전을 위해 추가
            raise last_exception
        
        return wrapper
    return decorator