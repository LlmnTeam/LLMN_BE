# app/services/rag_service.py
import asyncio
import logging

from queue import Queue
from threading import Thread
from typing import Any, Dict, List
from langchain.callbacks.base import BaseCallbackHandler
from langchain.schema import LLMResult
from langchain_openai import ChatOpenAI

logger = logging.getLogger(__name__)

MODEL_NAME = "gpt-4o-mini"
STREAMING_DELAY = 0.1  # 토큰 간 스트리밍 지연 시간(초)

class StreamingHandler(BaseCallbackHandler):
    def __init__(self, queue) -> None:
        super().__init__()
        self._queue = queue
        self._stop_signal = None
        logger.debug("스트리밍 핸들러 초기화 완료")

    # 새로운 토큰이 생성될 때 호출 => 생성된 토큰을 큐에 넣어 비동기적으로 전달
    def on_llm_new_token(self, token: str, **kwargs) -> None:
        self._queue.put(token)

    # LLM이 텍스트 생성을 시작할 때 호출
    def on_llm_start(self, serialized: Dict[str, Any], prompts: List[str], **kwargs: Any) -> None:
        logger.debug("텍스트 생성 시작")

    # LLM이 텍스트 생성을 완료했을 때 호출 => 생성 종료 신호를 큐에 넣어 생성을 중단
    def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        logger.debug("텍스트 생성 완료")
        self._queue.put(self._stop_signal)

class RagService:
    def __init__(self, api_key: str):
        self.streamer_queue = Queue() # 토큰을 비동기적으로 전달하기 위한 저장소로 사용
        self.streaming_handler = StreamingHandler(queue=self.streamer_queue)
        self.LLM = ChatOpenAI(
            model=MODEL_NAME,
            streaming=True,
            callbacks=[self.streaming_handler],
            openai_api_key=api_key
        )

    # LLM 호출
    def generate(self, llm, text):
        try:
            llm.invoke(text)
        except Exception as e:
            logger.error(f"텍스트 생성 중 오류 발생: {str(e)}")
            self.streamer_queue.put(None)  # 오류 발생 시 생성 종료 신호

    # 별도의 스레드를 생성 (텍스트 생성을 백그라운드에서 처리하기 위해)
    def start_generation(self, llm, text):
        thread = Thread(
            target=self.generate, 
            kwargs={"llm": llm, "text": text},
            daemon=True  # 프로세스 종료 시 스레드가 블로킹되지 않도록 함
        )
        thread.start()

    # 비동기적으로 스트리밍 방식으로 텍스트를 생성
    async def generate_text_streaming(self, text: str):
        self.start_generation(self.LLM, text)

        try:
            while True:
                # 큐에서 토큰 또는 중지 신호 가져오기
                value = self.streamer_queue.get()

                if value is None:
                    break
                    
                yield value 

                # 처리된 항목 표시 및 스트리밍 속도 제어
                self.streamer_queue.task_done() 
                await asyncio.sleep(STREAMING_DELAY)
        except Exception as e:
            logger.error(f"스트리밍 생성 중 오류 발생: {str(e)}")