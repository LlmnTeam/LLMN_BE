# app/services/rag_service.py
import asyncio
import logging

from queue import Queue
from threading import Thread
from typing import Any
from langchain.callbacks.base import BaseCallbackHandler
from langchain.schema import LLMResult
from langchain_openai import ChatOpenAI
from app.core.conversation_config import conversation_settings as cs

logger = logging.getLogger(__name__)

# LLM 응답을 실시간으로 스트리밍하기 위한 콜백 핸들러
class StreamingHandler(BaseCallbackHandler):
    def __init__(self, queue) -> None:
        super().__init__()
        self._queue = queue
        self._stop_signal = None

    # 토큰 생성 시 큐에 추가 (비동기적으로 전달)
    def on_llm_new_token(self, token: str, **kwargs) -> None:
        self._queue.put(token)

    # LLM 생성 완료 시 종료 신호 전송
    def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        self._queue.put(self._stop_signal)

# LLM 응답을 비동기 스트리밍으로 제공하는 서비스
class RagService:
    def __init__(self, api_key: str):
        self.streamer_queue = Queue() # 토큰 전달용 큐
        self.streaming_handler = StreamingHandler(queue=self.streamer_queue)
        self.LLM = ChatOpenAI(
            model=cs.LLM_MODEL_NAME,
            streaming=True,
            callbacks=[self.streaming_handler],
            openai_api_key=api_key
        )

    # LLM 응답을 비동기 스트림으로 변환
    async def generate_text_streaming(self, text: str):
        # 별도 스레드에서 LLM 실행
        self._start_generation(self.LLM, text)

        try:
            while True: 
                # 큐에서 토큰 가져와 응답으로 전달
                value = self.streamer_queue.get() 

                if value is None:
                    break
                    
                yield value 

                # 큐 작업 완료 표시 및 속도 제어
                self.streamer_queue.task_done() 
                await asyncio.sleep(cs.STREAMING_DELAY)
        except Exception as e:
            logger.error(f"스트리밍 생성 중 오류 발생: {str(e)}")
    
    # LLM 호출을 별도 스레드에서 실행
    def _start_generation(self, llm, text):
        thread = Thread(
            target=self._generate, 
            kwargs={"llm": llm, "text": text},
            daemon=True  
        )
        thread.start()
    
    # 실제 LLM 호출 처리
    def _generate(self, llm, text):
        try:
            llm.invoke(text)
        except Exception as e:
            logger.error(f"텍스트 생성 중 오류 발생: {str(e)}")
            self.streamer_queue.put(None) 