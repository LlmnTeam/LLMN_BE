# main.py
from fastapi import FastAPI, HTTPException, status, Request, Depends
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from pydantic_settings import BaseSettings
import logging
import os
import redis
from langchain_openai import ChatOpenAI
from langchain.prompts import PromptTemplate 
from langchain.callbacks.base import BaseCallbackHandler
from fastapi.middleware.cors import CORSMiddleware
from typing import Any, Dict, List
from queue import Queue
from threading import Thread
import asyncio
from langchain.schema import LLMResult
from dotenv import set_key, load_dotenv
import tiktoken
from jose import JWTError, jwt

app = FastAPI()

# logs 디렉토리 경로
logs_dir = os.getenv("LOGS_DIR", "/project/logs")

# 현재 디렉토리의 .env 파일 경로
env_file_path = os.path.join(os.path.dirname(__file__), ".env")

# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],  
    allow_headers=["*"], 
)

# 환경 변수 설정
class Settings(BaseSettings):
    OPENAI_API_KEY: str

    class Config:
        env_file = os.path.join(os.path.dirname(__file__), ".env")

settings = Settings()

# 레디스 설정
r = redis.Redis(host='redis', port=6379, db=0, decode_responses=True)

# 로깅 설정
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Tiktoken 설정 
encoding = tiktoken.get_encoding('cl100k_base')

# JWT
SECRET_KEY = "MySecretKey" 
ALGORITHM = "HS512"         

# DTO
class LogRequest(BaseModel):
    content: str

class Question(BaseModel):
    question: str

class LogFile(BaseModel):
    name: str

class  LogFilesRequest(BaseModel):
    logFiles: list[LogFile]
    question: str
    isFirstQuestion: bool  # 첫 번째 질문 여부를 나타내는 변수

class ValidateAPIRequest(BaseModel):
    apiKey: str

class EnvUpdateRequest(BaseModel):
    key: str
    value: str

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

class Rag_Service:
    # 스트리밍 핸들러와 LLM을 초기화
    def __init__(self):
        self.streamer_queue = Queue() # 토큰을 비동기적으로 전달하기 위한 저장소로 사용
        self.streaming_handler = StreamingHandler(queue=self.streamer_queue)
        self.LLM = ChatOpenAI(
            model="gpt-4o-mini",
            streaming=True,
            callbacks=[self.streaming_handler],
            openai_api_key=settings.OPENAI_API_KEY
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

class ConversationManager:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.key = f"conversation:{self.user_id}"
        self.summary_key = f"summary:{self.user_id}"
        self.max_token_length = 70000  # 최대 토큰 수
        self.keep_recent_messages = 15  # 요약 후 유지할 최근 대화 수

    # 대화 히스토리에 사용자 입력과 시스템 응답을 추가
    def add_to_history(self, user_input: str, system_response: str):
        r.rpush(self.key, f"User: {user_input}", f"System: {system_response}")
        r.expire(self.key, 1800)  

        # 대화 토큰 수 체크
        if self.calculate_total_tokens() > self.max_token_length:
            self.summarize_conversation()

    # 최근 n개의 대화 히스토리(질문/응답) 가져오기
    def get_recent_conversation(self):
        return r.lrange(self.key, 0, -1)

    # 대화 히스토리 포맷팅 
    def format_conversation(self):
        conversation = []

        # 요약이 존재하면 추가
        summary = r.get(self.summary_key)
        if summary:
            conversation.append("Previous Conversation Summary:\n" + summary)

        # 최근 대화 메시지 추가
        recent_messages = self.get_recent_conversation()
        conversation.extend(recent_messages)
        return "\n".join(conversation)
    
    @staticmethod
    def count_tokens(text: str) -> int:
        return len(encoding.encode(text))

    def calculate_total_tokens(self):
        total_tokens = 0
        messages = r.lrange(self.key, 0, -1)

        for message in messages:
            total_tokens += ConversationManager.count_tokens(message)
        return total_tokens
    
    # 대화 요약 생성
    def summarize_conversation(self):
        total_length = r.llen(self.key)
        num_messages_to_summarize = total_length - self.keep_recent_messages * 2  # 사용자와 시스템 메시지 각각 포함

        if num_messages_to_summarize <= 0:
            logger.info("요약할 메시지가 없습니다. 요약 수행이 생략됩니다.")
            return  

        # 요약 대상 메시지 가져오기
        messages_to_summarize = r.lrange(self.key, 0, num_messages_to_summarize - 1)
        conversation_text = "\n".join(messages_to_summarize)

        # 요약 생성 프롬프트 구성
        summarization_prompt = (
            "Please summarize the following conversation. Focus on key points, issues, and resolutions.\n\n"
            f"{conversation_text}\n"
            "Summary:"
        )

        # LLM을 사용하여 요약 생성
        summarizer = ChatOpenAI(
            model="gpt-4o-mini",
            temperature=0.3,
            max_tokens=750,
            openai_api_key=settings.OPENAI_API_KEY
        )

        try:
            summary_response = summarizer.invoke(summarization_prompt)
            summary_text = summary_response.strip()

            # 요약 결과 Redis에 저장
            r.set(self.summary_key, summary_text)
            logger.info(f"대화 요약 성공: {num_messages_to_summarize}개의 메시지 요약됨.")

            # 요약된 메시지 히스토리에서 제거
            r.ltrim(self.key, num_messages_to_summarize, -1)
        except Exception as e:
            logger.error(f"요약 중 오류 발생: {str(e)}")
    
    def clear_conversation(self):
        r.delete(self.key)
        r.delete(self.summary_key)

######################################################################################################

async def validate_openai_api_key(api_key: str) -> bool:
    try:
        # OpenAI Chat Model 호출을 위한 간단한 테스트 프롬프트
        chatmodel = ChatOpenAI(
            model="gpt-4o-mini",
            temperature=0.3,
            max_tokens=10,
            openai_api_key=api_key
        )
        
        prompt = (
            "This is a test prompt to validate the OpenAI API key. "
            "You can respond with just 'Hi'."
        )
        
        prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
        formatted_prompt = prompt_template.format(prompt=prompt)

        # API 호출
        response = chatmodel.invoke(formatted_prompt)
        
        return True
    except Exception as e:
        # 오류 발생 시 유효하지 않음
        return False

async def generate_log_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system log analyst. Summarize and detect anomalies in the following system logs.\n"
        "Focus only on essential information for problem-solving.\n"
        "\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean.\n"
        "Use icons or emojis (e.g., 📊 for summaries, ❗ for errors, ⚠️ for warnings, ℹ️ for info, 🚨 for anomalies, 🔍 for analysis required, 💡 for recommended actions, and 🔔 for critical alerts) to clearly separate sections and highlight key points.\n"
        "Only list detected items. If fewer than three events or anomalies are detected, list only those present and avoid empty slots.\n"
        "Include 'None' if there are no events or anomalies to report.\n"
        "Ensure that all numerical values (e.g., total occurrences) are calculated precisely based on the input data provided. Do not assume or estimate values; use the actual data for calculations.\n"
        "Ensure all results and conclusions are directly based on the provided data patterns and metrics."
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Log Summary ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "📊 [일반적인 요약]\n"
        "- 주요 이벤트\n"
        "   1. [Event 1]\n"
        "   2. [Event 2]\n"
        "   3. [Event 3]\n"
        "   ...(continue numbering as needed)\n"
        "- 발생 빈도\n"
        "   - ❗ERROR: [number of ERRORs]\n"
        "   - ⚠️ WARN: [number of WARNs]\n"
        "   - ℹ️ INFO: [number of INFOs]\n"
        "\n"
        "🚨 [이상 탐지 요약]\n"
        "- 탐지된 비정상 패턴\n"
        "   1. [Abnormal Pattern 1]: [Impact]\n"
        "   2. [Abnormal Pattern 2]: [Impact]\n"
        "   3. [Abnormal Pattern 3]: [Impact]\n"
        "   ...(continue numbering as needed)\n"
        "- 권장 조치\n"
        "   1. [Actionable Recommendation 1]\n"
        "   2. [Actionable Recommendation 2]\n"
        "   3. [Actionable Recommendation 3]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "🔍 [긴급 여부 체크]\n"
        "- Immediate Risk: Respond with `true` if immediate action is needed for critical system risks like severe downtime or operational disruptions; otherwise, `false`.\n"
        "- [true/false]"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=750,
        openai_api_key=settings.OPENAI_API_KEY
    )
    
    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    # OpenAI API 호출
    response = chatmodel.invoke(formatted_prompt)
    response_text = response.content  
    
    # "🔍 [긴급 여부 체크]" 줄이 시작되는 인덱스 찾기
    urgency_start_index = response_text.find("🔍 [긴급 여부 체크]")
    
    # log_summary와 is_urgent를 분리
    if urgency_start_index != -1:
        log_summary = response_text[:urgency_start_index].strip()  
        is_urgent_line = response_text[urgency_start_index:].split('\n')[-1].strip()  
        is_urgent = "true" in is_urgent_line.lower()
    else:
        log_summary = response_text.strip()  # 전체를 요약으로 보고 처리
        is_urgent = False  # 기본값

    return log_summary, is_urgent

async def generate_performance_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance analyst. Summarize and identify abnormal patterns in the following performance metrics.\n"
        "Focus on essential details for administrators to understand key events and anomalies. Only include critical and urgent recommendations.\n"
        "\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean.\n"
        "For abnormal patterns, focus on unusual spikes, sustained high usage, or significant deviations from typical values.\n"
        "Use icons or emojis (e.g., 📈 for summaries, 💻 for CPU, 💽 for memory, ⬇️ for network receive, ⬆️ for network send, ⚠️ for anomalies, and 🔧 for recommended actions) to clearly separate sections and highlight key points.\n"
        "Only list detected items. If fewer than three events or anomalies are detected, list only those present and avoid empty slots.\n"
        "When specifying 'maximum' values, ensure that the corresponding occurrence time is accurately extracted from the data. Avoid assumptions or approximations.\n"
        "Ensure that all numerical values (e.g., averages, maximums) are calculated precisely based on the input data provided. Do not assume or estimate values; use the actual data for calculations.\n"
        "Ensure all results and conclusions are directly based on the provided data patterns and metrics."
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Performance Summary ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "📈 [성능 개요]"
        "   - 💻 CPU\n"
        "     - 평균 사용량: [평균 CPU 사용량]%\n"
        "     - 최대 사용량: [최대 CPU 사용량]% (발생 시간: [최대 시간])\n"
        "   - 💽 메모리\n"
        "     - 평균 사용량: [평균 메모리 사용량] MB\n"
        "     - 최대 사용량: [최대 메모리 사용량] MB (발생 시간: [최대 시간])\n"
        "   - ⬇️ 네트워크 수신\n"
        "     - 평균 수신량: [평균 수신량] KB\n"
        "     - 최대 수신량: [최대 수신량] KB (발생 시간: [최대 시간])\n"
        "   - ⬆️ 네트워크 송신\n"
        "     - 평균 송신량: [평균 송신량] KB\n"
        "     - 최대 송신량: [최대 송신량] KB (발생 시간: [최대 시간])\n"
        "\n"
        "⚠️ [탐지된 비정상 패턴]\n"
        "   1. [Abnormal Pattern 1]: [Impact]\n"
        "   2. [Abnormal Pattern 2]: [Impact]\n"
        "   3. [Abnormal Pattern 3]: [Impact]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "🔧 [권장 조치]\n"
        "   1. [Actionable Recommendation 1]\n"
        "   2. [Actionable Recommendation 2]\n"
        "   3. [Actionable Recommendation 3]\n"
        "   ...(continue numbering as needed)\n"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=500,
        openai_api_key=settings.OPENAI_API_KEY
    )
    
    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    response = chatmodel.invoke(formatted_prompt)
    performance_summary = response.content  

    return performance_summary

async def generate_daily_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance and application log analyst. Generate a daily key summary report based on application and performance logs.\n"
        "Focus on key events, abnormal patterns, and immediate actions required to resolve issues.\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean.\n"
        "Use icons (e.g., 🔍 for the report, ❗ for errors, ⚠️ for warnings, 📊 for performance overview, 🔥 for high utilization, 💻 for CPU, 💽 for memory, ⬇️ for network receive, ⬆️ for network send, 🚨 for anomalies, and 🔧 for recommended actions) to clearly separate sections and highlight key points.\n"
        "Only list detected items. If fewer than three events or anomalies are detected, list only those present and avoid empty slots.\n"
        "When specifying 'maximum' values, ensure that the corresponding occurrence time is accurately extracted from the data. Avoid assumptions or approximations.\n"
        "Ensure that all numerical values (e.g., total occurrences, averages, maximums) are calculated precisely based on the input data provided. Do not assume or estimate values; use the actual data for calculations.\n"
        "Ensure all results and conclusions are directly based on the provided data patterns and metrics."
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Daily Key Summary Report ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "🔍 일일 핵심 요약 리포트\n"
        "\n"
        "⚠️ [주요 경고 및 오류]\n"
        "  - 경고/오류 항목들\n"
        "    1. ❗[Critical warning or error 1]: [Impact]\n"
        "       - 원인: [Potential root cause]\n"
        "    2. ❗[Critical warning or error 2]: [Impact]\n"
        "       - 원인: [Potential root cause]\n"
        "    3. ❗[Critical warning or error 3]: [Impact]\n"
        "       - 원인: [Potential root cause]\n"
        "    ...(continue numbering as needed)\n"
        "\n"
        "  - 발생 빈도\n"
        "    - ERROR: [Total number of ERRORs]\n"
        "    - WARN: [Total number of WARNs]\n"
        "\n"
        "📊 [시스템 성능 개요]\n"
        "  - 💻 CPU: 평균 [평균 CPU 사용량]%, 최대 [최대 CPU 사용량]% (발생 시간: [최대 CPU 사용량 발생 시간])\n"
        "  - 💽 메모리: 평균 [평균 메모리 사용량] MB, 최대 [최대 메모리 사용량] MB (발생 시간: [최대 메모리 사용량 발생 시간])\n"
        "  - ⬇️ 네트워크 수신: 평균 [평균 수신량] MB, 최대 [최대 수신량] MB (발생 시간: [최대 수신량 발생 시간])\n"
        "  - ⬆️ 네트워크 송신: 평균 [평균 송신량] MB, 최대 [최대 송신량] MB (발생 시간: [최대 송신량 발생 시간])\n"
        "\n"
        "🚨 [탐지된 비정상 패턴]\n"
        "  - [Abnormal Pattern 1]: [Impact]\n"
        "  - [Abnormal Pattern 2]: [Impact]\n"
        "  - [Abnormal Pattern 3]: [Impact]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "🔧 [긴급 권장 조치]\n"
        "  - [Actionable Recommendation 1]\n"
        "  - [Actionable Recommendation 2]\n"
        "  - [Actionable Recommendation 3]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=1200,
        openai_api_key=settings.OPENAI_API_KEY
    )
    
    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    response = chatmodel.invoke(formatted_prompt)
    daily_summary = response.content  

    return daily_summary

async def generate_trend_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance and application log analyst. Generate a weekly long-term trend analysis report based on daily key summaries.\n"
        "The report should emphasize system performance trends, error/warning patterns, abnormal patterns, and provide predictions based on the analyzed data.\n"
        "Focus on identifying trends and drawing insights from weekly data.\n"
        "\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean.\n"
        "Use icons to organize sections (e.g., 📊 for report title, ❗ for errors, ⚠️ for warnings, 📈 for increasing trends, 📉 for decreasing trends, 💻 for CPU, 💽 for memory, ⬇️ for network receive, ⬆️ for network send, 🚨 for abnormal patterns, 🔍 for insights, 🔮 for predictions, and 🔧 for recommended actions).\n"
        "Only list detected items. If fewer than three events or anomalies are detected, list only those present and avoid empty slots.\n"
        "When specifying 'maximum' values, ensure that the corresponding occurrence time is accurately extracted from the data. Avoid assumptions or approximations.\n"
        "Ensure that all numerical values (e.g., total occurrences, averages, maximums, trends) are calculated precisely based on the input data provided. Do not assume or estimate values; use the actual data for calculations.\n"
        "Ensure all results and conclusions are directly based on the provided data patterns and metrics."
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Weekly Long-Term Trend Analysis Report ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "📊 주간 장기 트렌드 분석 리포트\n"
        "\n"
        "❗ [경고 및 오류 트렌드 분석]\n"
        "   - 주요 경고 및 오류 발생 추세\n"
        "     - ERROR 발생 총 횟수: [총 횟수]회\n"
        "     - WARN 발생 총 횟수: [총 횟수]회\n"
        "     - 주간 발생 추이: [요일별로 데이터 나열]\n"
        "       - 월요일: ERROR [횟수], WARN [횟수]\n"
        "       - 화요일: ERROR [횟수], WARN [횟수]\n"
        "       - … (각 요일별 상세 데이터)\n"   
        "     - 증가/감소 트렌드: 지난주 대비 [증가율/감소율]%\n"
        "\n"
        "   - 📋 주요 문제 유형\n"
        "     1. [Error Type 1] - [횟수]회 발생 (주로 [시간대]에 집중)\n"
        "        - 원인: [문제의 주요 원인]\n"
        "        - 영향: [이 문제로 인한 시스템 또는 서비스의 영향]\n"
        "        - 해결 조치: [권장되는 해결 방법]\n"
        "     2. [Error Type 2] - [횟수]회 발생 (주로 [시간대]에 집중)\n"
        "     3. [Error Type 3] - [횟수]회 발생 (주로 [시간대]에 집중)\n"
        "     ...(continue numbering as needed)\n"
        "\n"
        "   - 🔍 주요 인사이트\n"
        "     - 원인 분석: [주요 원인 A]\n"
        "        - 관련 지표: [관련된 성능 지표] (예: CPU, 메모리 등)\n"
        "        - 연관 문제: [이 원인과 연관된 다른 문제 또는 경고]\n"
        "     ...(include additional insights as needed)\n"
        "\n"
        "📈 [성능 지표 트렌드 분석]\n"
        "   - 💻 CPU 사용량 트렌드\n"
        "     - 주간 평균: [평균 CPU 사용량]%\n"
        "     - 최대 사용량: [최대 사용량]% (시간: [최대 사용 시간])\n"
        "     - 일별 CPU 사용량 추이: [요일별 데이터]\n"
        "\n"
        "   - 💽 메모리 사용량 트렌드\n"
        "     - 주간 평균: [평균 메모리 사용량] MB\n"
        "     - 최대 사용량: [최대 메모리 사용량] MB (시간: [최대 사용 시간])\n"
        "     - 일별 메모리 사용량 추이: [요일별 데이터]\n"
        "\n"
        "   - 📉 네트워크 트래픽 트렌드\n"
        "     - 수신량: 평균 [평균 수신량] MB, 최대 [최대 수신량] MB (발생 시간: [최대 수신량 시간])\n"
        "     - 송신량: 평균 [평균 송신량] MB, 최대 [최대 송신량] MB (발생 시간: [최대 송신량 시간])\n"
        "     - 일별 네트워크 트래픽 추이:\n"
        "        - 수신량: 월요일 [수신량] MB, 화요일 [수신량] MB, …\n"
        "        - 송신량: 월요일 [송신량] MB, 화요일 [송신량] MB, …\n"
        "     - 증가/감소 비율: 네트워크 수신 및 송신량의 주간 변화율 - [증가율/감소율]%"
        "\n"
        "🚨 [비정상 패턴 장기 분석]\n"
        "   - [Abnormal Pattern 1]\n"
        "     - 발생 횟수: [횟수]" 
        "     - 발생 시간대: [시간 범위]\n"
        "     - 연관 성능 지표: [연관된 성능 지표]\n"
        "   - [Abnormal Pattern 2]\n"
        "   - [Abnormal Pattern 3]\n"
        "     ...(continue numbering as needed)\n"
        "\n"
        "📊 [향후 예측 및 권장 조치]\n"
        "   - 🔮 예측\n"
        "     1. [Actionable Recommendation 1]\n"
        "       - 예상 문제: [예상되는 문제의 설명]\n"
        "       - 발생 가능성: [발생 가능성 수준] (낮음/중간/높음)\n"
        "       - 예측 근거: [주요 예측 근거 데이터 또는 분석 요약]\n"
        "       - 예상 영향: [문제가 시스템 또는 서비스에 미칠 수 있는 예상 영향]\n"
        "     2. [Actionable Recommendation 2]\n"
        "     3. [Actionable Recommendation 3]\n"
        "     ...(continue numbering as needed)\n"
        "\n"
        "   - 🔧 권장 조치\n"
        "     1. [Actionable Recommendation 1]\n"
        "     2. [Actionable Recommendation 2]\n"
        "     3. [Actionable Recommendation 3]\n"        
        "     ...(continue numbering as needed)\n"
        "\n"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=2000,
        openai_api_key=settings.OPENAI_API_KEY
    )
    
    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    response = chatmodel.invoke(formatted_prompt)
    trend_summary = response.content  

    return trend_summary

async def generate_recommend(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance analyst. Your task is to provide concise, actionable recommendations based on application logs and performance data from the past 6 hours.\n"
        "Focus on key performance issues and suggest immediate actions.\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean.\n"
        "Use icons to clearly organize recommendations and emphasize the type of action (e.g., 🔧 for maintenance actions, 🚀 for optimization actions, ⚠️ for urgent actions).\n"
        "Provide a list of brief, clear recommendations, starting each item with a dash (-). Include multiple items under each type if needed, and avoid empty slots.\n"
        "List only the most critical actions needed and avoid unnecessary details.\n"
        "Ensure all results and conclusions are directly based on the provided data patterns and metrics."
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Recommendations ###\n"
        "Format the response as a list of brief, clear recommendations based on the input data:\n"
        "\n"
        "- ⚠️ [Immediate action for critical issue 1]\n"
        "- ⚠️ [Immediate action for critical issue 2]\n"
        "- 🔧 [Maintenance action recommendation 1]\n"
        "- 🔧 [Maintenance action recommendation 2]\n"
        "- 🚀 [Optimization action recommendation 1]\n"
        "- 🚀 [Optimization action recommendation 2]\n"
        "   ...(continue listing as needed for each type)\n"
        "\n"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=750,
        openai_api_key=settings.OPENAI_API_KEY
    )
    
    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    response = chatmodel.invoke(formatted_prompt)
    recommend = response.content  

    return recommend

async def generate_hourly_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system log and performance analyst. Analyze the logs and provide concise, one-line summaries focusing on critical issues or performance problems from the past hour.\n"
        "Only include urgent information that requires immediate attention. Ignore minor issues that do not significantly impact system performance.\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean.\n"
        "Focus on critical errors, performance issues, or warnings that require immediate action. Each summary should include the event time, criticality level, a brief issue description, and a recommended action (if needed).\n"
        "Use the following icons to indicate priority: ❗ for Critical, ⚠️ for Warning, ℹ️ for Info. Structure each summary as:\n  - [Criticality] [Event Time]: [Issue Description]. [Recommended Action]\n"
        "When specifying 'maximum' values, ensure that the corresponding occurrence time is accurately extracted from the data. Avoid assumptions or approximations.\n"
        "Ensure all results and conclusions are directly based on the provided data patterns and metrics."
        "\n"
        "### Log and Performance Data ###\n"
        f"{content}\n"
        "\n"
        "### One-Line Summaries ###\n"
        "Summarize the following logs, listing only critical items:\n"
        "\n"
        "1. ❗ [Event Time]: [Critical Issue Description]. [Immediate Action]\n"
        "2. ⚠️ [Event Time]: [Warning Description]. [Suggested Action]\n"
        "3. ℹ️ [Event Time]: [Informational Description]. [Recommended Action]\n"
        "   ...(continue as needed for all high-priority issues)\n"
        "\n"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=750,
        openai_api_key=settings.OPENAI_API_KEY
    )
    

    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    response = chatmodel.invoke(formatted_prompt)
    hourly_summary = response.content

    return hourly_summary

def read_log_file(file_path: str):
    try:
        with open(file_path, "r", encoding="utf-8") as file:
            return file.read()
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File {file_path} not found")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

def combine_logs_and_question(log_files_request: LogFilesRequest, conversation_manager: ConversationManager) -> str:
    log_message_builder = []

    # 첫 번째 질문일 경우 대화 히스토리와 요약 삭제
    if log_files_request.isFirstQuestion:
        conversation_manager.clear_conversation()

    # 첫 번째 질문일 경우에만 로그 파일의 내용을 추가
    if log_files_request.isFirstQuestion:
        for logFile in log_files_request.logFiles:
            file_path = os.path.join(logs_dir, logFile.name)
            log_content = read_log_file(file_path)
            log_message_builder.append(f"### Log file: {logFile.name} ###\n{log_content}\n")

    prompt = ""

    # 첫 번째 질문일 경우에만 Persona 프롬프트 추가
    if log_files_request.isFirstQuestion:
        prompt = (
            "### Persona ###\n"
            "You are an expert system log analyst. Your task is to analyze the following log files and provide insightful, detailed responses based on system performance, errors, and abnormal patterns.\n"
            "Focus on the key issues found in the logs and the user's question.\n"
            "Your responses should be in Korean.\n"
        )

        prompt += f"\n### Log Files ###\n{''.join(log_message_builder)}\n"

    # 대화 히스토리 및 질문 추가
    prompt += (
        "\n### Conversation History ###\n"
        f"{conversation_manager.format_conversation()}\n"  
        "\n"
        "### User Question ###\n"
        f"{log_files_request.question}\n"
    )

    return prompt

def get_user_id_from_token(request: Request):
    auth_header = request.headers.get("Authorization")
    if auth_header is None or not auth_header.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing authorization header",
        )
    token = auth_header[len("Bearer "):]
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        user_id: int = payload.get("id")
        if user_id is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="User ID not found in token",
            )
        return user_id
    except JWTError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is invalid or expired",
        ) from e

#################################################################################################

@app.post("/process/logSummary")
async def process_log_summary(request: LogRequest):
    log_summary, is_urgent = await generate_log_summary(request.content)
    
    # 결과를 JSON으로 반환
    return {
        "logSummary": log_summary,
        "isUrgent": is_urgent
    }

@app.post("/process/performanceSummary")
async def process_performance_summary(request: LogRequest):
    performance_summary = await generate_performance_summary(request.content)
    
    return {
        "performanceSummary": performance_summary
    }

@app.post("/process/dailySummary")
async def process_daily_summary(request: LogRequest):
    daily_summary = await generate_daily_summary(request.content)
    
    return {
        "dailySummary": daily_summary
    }

@app.post("/process/trendSummary")
async def process_daily_summary(request: LogRequest):
    trend_summary = await generate_trend_summary(request.content)
    
    return {
        "trendSummary": trend_summary
    }

@app.post("/process/recommend")
async def process_recommend(request: LogRequest):
    recommend = await generate_recommend(request.content)
    
    return {
        "recommend": recommend
    }

@app.post("/process/hourlySummary")
async def process_hourly_summary(request: LogRequest):
    hourly_summary = await generate_hourly_summary(request.content)
    
    return {
        "hourlySummary": hourly_summary
    }

@app.post("/api/logs/question")
async def process_logs_and_question(
    request: LogFilesRequest,
    user_id: int = Depends(get_user_id_from_token)
):
    conversation_manager = ConversationManager(user_id)

    # 로그와 질문을 포함한 최종 질문 생성
    final_question = combine_logs_and_question(request, conversation_manager)

    # OpnAI API 호출
    rag_service = Rag_Service()

    # 스트리밍된 응답을 수집하기 위한 리스트
    response_collector = []

    async def stream_response():
        # OpenAI API가 토큰을 생성할 때마다, 응답을 수집하고 바로바로 chunk를 반환
        async for chunk in rag_service.generate_text_streaming(final_question):
            response_collector.append(chunk)  
            yield chunk

        # async 루프 완료(스트리밍이 종료) => 대화 히스토리에 저장
        complete_response = ''.join(response_collector)
        conversation_manager.add_to_history(request.question, complete_response)

    return StreamingResponse(stream_response(), media_type='text/event-stream')
    
@app.post("/validate-api-key")
async def validate_api_key(request: ValidateAPIRequest):
    is_valid = await validate_openai_api_key(request.apiKey)
    
    if is_valid:
        return {"isValid": True}
    else:
        return {"isValid": False}
        
@app.post("/update-env")
async def update_env(request: EnvUpdateRequest):
    key, value = request.key, request.value

    try:
        # .env 파일 로드
        load_dotenv(env_file_path)
        
        # .env 파일에 key-value 쌍 저장
        set_key(env_file_path, key, f'"{value}"')

        # 환경 설정을 다시 로드하여 업데이트 적용
        global settings
        settings = Settings()  

        return {"success": True}
    
    except Exception as e:
        raise HTTPException(status_code=500, detail=f".env 파일 업데이트 실패: {str(e)}")