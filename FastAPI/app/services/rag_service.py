# app/services/rag_service.py
from queue import Queue
from threading import Thread
import asyncio
from typing import Any, Dict, List
from langchain.callbacks.base import BaseCallbackHandler
from langchain.schema import LLMResult
from langchain_openai import ChatOpenAI

# OpenAI API 스트리밍
class StreamingHandler(BaseCallbackHandler):
    def __init__(self, queue) -> None:
        super().__init__()
        self._queue = queue
        self._stop_signal = None
        print("Custom handler Initialized")

    # 새로운 토큰이 생성될 때 호출 => 생성된 토큰을 큐에 넣어 비동기적으로 전달
    def on_llm_new_token(self, token: str, **kwargs) -> None:
        self._queue.put(token)

    # LLM이 텍스트 생성을 시작할 때 호출
    def on_llm_start(
        self, serialized: Dict[str, Any], prompts: List[str], **kwargs: Any
    ) -> None:
        print("generation started")

    # LLM이 텍스트 생성을 완료했을 때 호출 => 생성 종료 신호를 큐에 넣어 생성을 중단
    def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        print("\ngeneration concluded")
        self._queue.put(self._stop_signal)

class RagService:
    def __init__(self, api_key: str):
        self.streamer_queue = Queue() # 토큰을 비동기적으로 전달하기 위한 저장소로 사용
        self.streaming_handler = StreamingHandler(queue=self.streamer_queue)
        self.LLM = ChatOpenAI(
            model="gpt-4o-mini",
            streaming=True,
            callbacks=[self.streaming_handler],
            openai_api_key=api_key
        )

    # LLM 호출
    def generate(self, llm, text):
        llm.invoke(text)

    # 별도의 스레드를 생성 (텍스트 생성을 백그라운드에서 처리하기 위해)
    def start_generation(self, llm, text):
        thread = Thread(target=self.generate, kwargs={"llm": llm, "text": text})
        thread.start()

    # 비동기적으로 스트리밍 방식으로 텍스트를 생성
    async def generate_text_streaming(self, text: str):
        self.start_generation(self.LLM, text)

        while True:
            value = self.streamer_queue.get() # 큐에서 토큰 또는 종료 신호를 가져옴

            if value == None:
                break
            yield value 

            # 큐에서 처리된 항목을 완료 처리 => 0.1초 동안 비동기적으로 대기하여 스트리밍 속도를 조정
            self.streamer_queue.task_done() 
            await asyncio.sleep(0.1) 