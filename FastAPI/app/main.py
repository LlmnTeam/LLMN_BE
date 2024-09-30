# main.py
from fastapi import FastAPI, HTTPException
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

app = FastAPI()

# logs 디렉토리 경로
current_dir = os.path.dirname(os.path.abspath(__file__))
logs_dir = os.path.join(current_dir, "..", "..", "logs")

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
r = redis.Redis(host='localhost', port=6379, db=0, decode_responses=True)

# 로깅 설정
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# DTO
class LogRequest(BaseModel):
    content: str

class Question(BaseModel):
    question: str

class LogFile(BaseModel):
    name: str

class  LogFilesRequest(BaseModel):
    userId: int
    logFiles: list[LogFile]
    question: str
    isFirstQuestion: bool  # 첫 번째 질문 여부를 나타내는 변수

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

    # 대화 히스토리에 사용자 입력과 시스템 응답을 추가
    def add_to_history(self, user_input: str, system_response: str):
        r.rpush(self.key, f"User: {user_input}", f"System: {system_response}")
        r.expire(self.key, 1800)  # TTL은 30분

    # 최근 n개의 대화 히스토리(질문/응답) 가져오기
    def get_recent_conversation(self, n=5):
        return r.lrange(self.key, -n*2, -1)  

    # 대화 히스토리 포맷팅 
    def format_conversation(self, n=5):
        conversation = self.get_recent_conversation(n)
        return "\n".join(conversation)  

######################################################################################################

async def generate_log_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system log analyst. Your task is to summarize and detect anomalies in the following system logs.\n"
        "Make sure to be concise but provide enough detail for an administrator to understand key events and anomalies.\n"
        "If there are fewer than three events or anomalies, only provide the relevant information. Do not force the list to reach three items.\n"
        "\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean and follow a structured format.\n"
        "Use icons or emojis to enhance readability and highlight key sections.\n"
        "Ensure that your response strictly adheres to the following format, and keep each section separate with two line breaks (`\\n\\n`).\n"
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### General Summary ###\n"
        "1. Summarize the key events from the log data.\n"
        "2. The summary should include the main [ERROR], [WARN], and [INFO] events.\n"
        "3. Format the response in the following structure.\n"
        "\n"
        "[📊 일반적인 요약]\n"
        "- 주요 이벤트\n"
        "   1. [First major event]\n"
        "   2. [Second major event]\n"
        "   3. [Third major event]\n"
        "   ...(continue numbering as needed)\n"
        "- 발생 빈도:\n"
        "   - ❗ERROR: [number of ERRORs]\n"
        "   - ⚠️ WARN: [number of WARNs]\n"
        "   - ℹ️ INFO: [number of INFOs]\n"
        "\n"
        "### Anomaly Detection ###\n"
        "1. Identify any abnormal patterns, such as repetitive errors or unusual occurrences.\n"
        "2. Format the response in the following structure with icons or emojis to improve readability:\n"
        "\n"
        "[🚨 이상 탐지 요약]\n"
        "- 탐지된 비정상 패턴\n"
        "   1. [First detected anomaly]\n"
        "   2. [Second detected anomaly]\n"
        "   3. [Third detected anomaly]\n"
        "   ...(continue numbering as needed)\n"
        "- 권장 조치\n"
        "   1. [💡 First recommended action]\n"
        "   2. [💡 Second recommended action]\n"
        "   3. [💡 Third recommended action]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "### Urgency Check ###\n"
        "1. Based on the logs, determine whether immediate action is **critically** required for system performance issues that may cause severe downtime, loss of service, or significant operational disruptions.\n"
        "2. Respond with `true` only if the system is at immediate risk of crashing or facing critical failure, otherwise respond with `false`.\n"
        "\n"
        "### Fixed Format ###\n"
        "Ensure that the response follows this exact structure:\n"
        "1. [General Summary Section]\n"
        "2. [Anomaly Detection Section]\n"
        "3. [Urgency Check: true/false]\n"
    )

    chatmodel = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.3,
        max_tokens=750,
        openai_api_key=settings.OPENAI_API_KEY
    )
    
    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    # Chat 모델 호출하여 응답 받기
    response = chatmodel.invoke(formatted_prompt)
    response_text = response.content  # response content에서 텍스트 추출

    # 요약과 이상 탐지 요약을 분리
    response_lines = response_text.strip().split('\n\n')
    general_summary = response_lines[0].strip() if len(response_lines) > 0 else ""
    anomaly_summary = response_lines[1].strip() if len(response_lines) > 1 else ""

    # 긴급 체크 부분 파싱
    is_urgent_line = response_lines[2].strip() if len(response_lines) > 2 else "false"
    is_urgent = is_urgent_line.lower() == "true"  # 문자열을 boolean으로 변환

    return general_summary, anomaly_summary, is_urgent

async def generate_performance_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance analyst. Your task is to summarize and identify abnormal patterns in the following performance metrics.\n"
        "Make sure to be concise but provide enough detail for an administrator to understand key events and anomalies.\n"
        "If there are fewer than three events or anomalies, only provide the relevant information. Do not force the list to reach three items.\n"
        "Only include critical and urgent recommendations in the response.\n"
        "\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean and follow a structured format.\n"
        "Use icons or emojis to enhance readability and highlight key sections.\n"
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Performance Summary ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "- 성능 개요"
        "   - CPU\n"
        "     - 평균 사용량: [평균 CPU 사용량]%\n"
        "     - 최대 사용량: [최대 CPU 사용량]% (발생 시간: [최대 시간])\n"
        "   - 메모리\n"
        "     - 평균 사용량: [평균 메모리 사용량] MB\n"
        "     - 최대 사용량: [최대 메모리 사용량] MB (발생 시간: [최대 시간])\n"
        "   - 네트워크 수신\n"
        "     - 평균 수신량: [평균 수신량] KB\n"
        "     - 최대 수신량: [최대 수신량] KB (발생 시간: [최대 시간])\n"
        "   - 네트워크 송신\n"
        "     - 평균 송신량: [평균 송신량] KB\n"
        "     - 최대 송신량: [최대 송신량] KB (발생 시간: [최대 시간])\n"
        "\n"
        "- 탐지된 비정상 패턴\n"
        "   1. [First detected abnormal pattern]\n"
        "   2. [Second detected abnormal pattern]\n"
        "   3. [Third detected abnormal pattern]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "- 권장 조치\n"
        "   1. [First critical action]\n"
        "   2. [Second critical action]\n"
        "   3. [Third critical action]\n"
        "   ...(continue numbering as needed)\n"
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
    performance_summary = response.content  

    return performance_summary

async def generate_daily_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance and application log analyst. Your task is to generate a daily key summary report based on application and performance logs.\n"
        "The report should focus on key events, abnormal patterns, and immediate actions required to resolve issues.\n"
        "If there are fewer than three events or anomalies, only provide the relevant information. Do not force the list to reach three items.\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean and provide only the most critical and urgent information.\n"
        "Use icons or emojis to highlight key sections.\n"
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Daily Key Summary Report ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "🔍 일일 핵심 요약 리포트\n"
        "\n"
        "1. 주요 경고 및 오류\n"
        "- 경고/오류 항목들\n"
        "   1. [First critical warning or error]\n"
        "   2. [Second critical warning or error]\n"
        "   3. [Third critical warning or error]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "- 발생 빈도\n"
        "   - ERROR: [Total number of ERRORs]\n"
        "   - WARN: [Total number of WARNs]\n"
        "\n"
        "2. 시스템 성능 개요\n"
        "- CPU 사용량: 평균 [평균 CPU 사용량]%, 최대 [최대 CPU 사용량]% (발생 시간: [최대 CPU 사용량 발생 시간])\n"
        "- 메모리 사용량: 평균 [평균 메모리 사용량] MB, 최대 [최대 메모리 사용량] MB (발생 시간: [최대 메모리 사용량 발생 시간])\n"
        "- 네트워크 수신량: 평균 [평균 수신량] MB, 최대 [최대 수신량] MB (발생 시간: [최대 수신량 발생 시간])\n"
        "- 네트워크 송신량: 평균 [평균 송신량] MB, 최대 [최대 송신량] MB (발생 시간: [최대 송신량 발생 시간])\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "3. 탐지된 비정상 패턴\n"
        "- [First detected abnormal pattern]\n"
        "- [Second detected abnormal pattern]\n"
        "- [Third detected abnormal pattern]\n"
        "   ...(continue numbering as needed)\n"
        "\n"
        "4. 긴급 권장 조치\n"
        "- [First critical action]\n"
        "- [Second critical action]\n"
        "- [Third critical action]\n"
        "   ...(continue numbering as needed)\n"
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
    daily_summary = response.content  

    return daily_summary

async def generate_trend_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance and application log analyst. Your task is to generate a weekly long-term trend analysis report based on daily key summaries.\n"
        "The report should emphasize system performance trends, error/warning patterns, abnormal patterns, and provide predictions based on the analyzed data.\n"
        "Focus more on long-term analysis and trends.\n"
        "Use numbers, trends, and clear insights to present the analysis and predictions.\n"
        "\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean and focus on trends, patterns, and projections over the past week."
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Weekly Long-Term Trend Analysis Report ###\n"
        "Format the response in the following structure:\n"
        "\n"
        "📊 주간 장기 트렌드 분석 리포트\n"
        "\n"
        "1. 경고 및 오류 트렌드 분석\n"
        " 1.1 주요 경고 및 오류 발생 추세\n"
        "- 주간 동안 총 발생 횟수\n"
        "  - ERROR: [ERROR 발생 총 횟수]회\n"
        "  - WARN: [WARN 발생 총 횟수]회\n"
        "- 일별 발생 추이\n"
        "  - 월요일: ERROR [횟수], WARN [횟수]\n"
        "  - 화요일: ERROR [횟수], WARN [횟수]\n"
        "  - … (각 요일별 상세 데이터)\n"
        "- 증가 또는 감소 트렌드\n"
        "  - 지난주 대비 [ERROR/WARN] 발생 비율: +[증가율]% 또는 -[감소율]%\n"
        "  > 예시: ERROR 발생 횟수가 지난주 대비 30% 증가했습니다.\n"
        "\n"
        " 1.2 주요 문제 유형\n"
        "- 가장 자주 발생한 문제 유형\n"
        "   1. [오류 또는 경고 A] - [횟수]회 발생\n"
        "   2. [오류 또는 경고 B] - [횟수]회 발생\n"
        "   > 이 오류는 [시간대]에 집중적으로 발생했습니다.\n"
        "\n"
        " 1.3 분석 및 인사이트\n"
        "- 주요 원인 분석\n"
        "   - [주요 원인 A]: 주로 [시간대]에 발생한 오류/경고로 인해 시스템 문제가 발생했습니다.\n"
        "- 시스템 안정성: 시스템 안정성은 주간 평균 [안정성 지표]로 나타났으며, 이는 지난주 대비 [변화량]입니다.\n"
        "   > 예시: 시스템 안정성은 지난주보다 10% 향상되었으나, 특정 시간대에서 반복적인 오류가 나타나고 있습니다.\n"
        "\n"
        "2. 성능 지표 트렌드 분석\n"
        " 2.1 CPU 사용량 트렌드\n"
        "- 주간 CPU 사용량 분석\n"
        "  - 평균 사용량: [평균 CPU 사용량]%\n"
        "  - 최대 사용량: [최대 사용량]% (발생 시간: [최대 사용량 발생 시간])\n"
        "- 일별 CPU 사용량 변화:\n"
        "  - 월요일: [CPU 사용량]%\n"
        "  - 화요일: [CPU 사용량]%\n"
        "  - … (요일별 데이터)\n"
        "- 증가 또는 감소 트렌드: 전주 대비 CPU 사용량 [증가/감소]\n"
        "  > 예시: CPU 사용량이 수요일 오후에 집중적으로 증가하였습니다.\n"
        "\n"
        " 2.2 메모리 사용량 트렌드\n"
        "- 주간 메모리 사용량 분석\n"
        "  - 평균 사용량: [평균 메모리 사용량] MB\n"
        "  - 최대 사용량: [최대 메모리 사용량] MB (발생 시간: [최대 메모리 사용량 발생 시간])\n"
        "- 일별 메모리 사용량 변화\n"
        "  - 월요일: [메모리 사용량] MB\n"
        "  - 화요일: [메모리 사용량] MB\n"
        "  - … (요일별 데이터)\n"
        "- 증가 또는 감소 트렌드: 메모리 사용량은 주중 [증가/감소] 경향을 보임.\n"
        "  > 예시: 금요일에 메모리 사용량이 급격히 증가하였습니다.\n"
        "\n"
        " 2.3 네트워크 트래픽 트렌드\n"
        "- 네트워크 수신량/송신량 분석\n"
        "  - 평균 수신량: [평균 수신량] MB\n"
        "  - 최대 수신량: [최대 수신량] MB (발생 시간: [최대 수신량 발생 시간])\n"
        "  - 평균 송신량: [평균 송신량] MB\n"
        "  - 최대 송신량: [최대 송신량] MB (발생 시간: [최대 송신량 발생 시간])\n"
        "- 일별 트래픽 변화\n"
        "  - 월요일: 수신 [수신량] MB / 송신 [송신량] MB\n"
        "  - 화요일: 수신 [수신량] MB / 송신 [송신량] MB\n"
        "  - … (요일별 데이터)\n"
        "- 증가 또는 감소 트렌드: 수신 및 송신량 모두 전주 대비 [증가/감소]\n"
        "  > 예시: 월요일과 금요일에 네트워크 트래픽이 급증하였습니다.\n"
        "\n"
        "3. 비정상 패턴 장기 분석\n"
        "- 주요 비정상 패턴 요약\n"
        "  1. [비정상 패턴 A]: 주간 동안 [횟수]회 발생\n"
        "     - 발생 시간대: [시간 범위]\n"
        "     - 연관된 성능 지표: [연관된 성능 지표]\n"
        "  2. [비정상 패턴 B]: 주간 동안 [횟수]회 발생\n"
        "     - 발생 시간대: [시간 범위]\n"
        "     - 연관된 성능 지표: [연관된 성능 지표]\n"
        "- 비정상 패턴 발생 추세 분석\n"
        "  - 주간 동안 비정상 패턴의 증가/감소 추세를 분석.\n"
        "  > 예시: WebSocket 연결 실패 패턴이 주중 15% 증가하였으며, 이는 네트워크 상태의 불안정과 관련이 있을 수 있습니다.\n"
        "\n"
        "4. 향후 예측\n"
        "- 향후 발생 가능성 있는 문제:\n"
        "  - [예상 문제 1]: 주간 데이터를 바탕으로, 다음 주에 예상되는 문제는 [문제 설명]입니다.\n"
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
    trend_summary = response.content  

    return trend_summary

async def generate_recommend(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance analyst. Your task is to provide concise, actionable recommendations based on application logs and performance data from the past 6 hours.\n"
        "Your responses should be in Korean and focus on key performance issues and suggestions for immediate action.\n"
        "Use short, clear recommendations that directly address the most urgent and critical system problems.\n"
        "The format should be simple and easy to understand. Each recommendation should begin with a dash (-).\n"
        "### Writing Guidelines ###\n"
        "Your responses should be in Korean and follow a structured format.\n"
        "\n"
        "### Input Data ###\n"
        f"{content}\n"
        "\n"
        "### Recommendations ###\n"
        "Format the response as a list of brief recommendations based on the input data:\n"
        "\n"
        "- [First recommendation]\n"
        "- [Second recommendation]\n"
        "- [Third recommendation]\n"
        "   ...(continue numbering as needed)\n"
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
        "You are an expert system log and performance analyst. Your task is to analyze the following logs and provide concise, one-line summaries highlighting critical issues or performance problems.\n"
        "Only include the most important information that requires immediate attention. Ignore minor issues or events that do not significantly impact system performance.\n"
        "Your responses should be in Korean"
        "The summaries should focus on important errors, performance issues, or warnings that require immediate attention. Each summary should include the time of the event, the criticality level, a brief description of the problem, and a recommended action if necessary.\n"
        "Each summary should be formatted as follows:\n"
        "[Criticality] [Event Time]: [Issue Description]. [Recommended Action]\n"
        "Use appropriate emojis for criticality: ❗ for Critical, ⚠️ for Warning, ℹ️ for Info.\n"
        "Ensure that the summaries are short, actionable, and easy to understand.\n"
        "\n"
        "### Log and Performance Data ###\n"
        f"{content}\n"
        "\n"
        "### One-Line Summaries ###\n"
        "1. [First summary]\n"
        "2. [Second summary]\n"
        "3. [Third summary]\n"
        "..."
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

        # 로그 파일 내용도 첫 번째 질문에만 포함
        prompt += f"\n### Log Files ###\n{''.join(log_message_builder)}\n"

    # 대화 히스토리 및 질문 추가
    prompt += (
        "\n### Conversation History ###\n"
        f"{conversation_manager.format_conversation()}\n"  # 이전 대화 히스토리 추가
        "\n"
        "### User Question ###\n"
        f"{log_files_request.question}\n"
    )

    return prompt

#################################################################################################

@app.post("/process/logSummary")
async def process_log_summary(request: LogRequest):
    general_summary, anomaly_summary, is_urgent = await generate_log_summary(request.content)
    
    # 결과를 JSON으로 반환
    return {
        "generalSummary": general_summary,
        "anomalySummary": anomaly_summary,
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

@app.post("/logs/question")
async def process_logs_and_question(log_files_request: LogFilesRequest):
    conversation_manager = ConversationManager(log_files_request.userId)

    # 로그와 질문을 포함한 최종 질문 생성
    final_question = combine_logs_and_question(log_files_request, conversation_manager)

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
        conversation_manager.add_to_history(log_files_request.question, complete_response)

    return StreamingResponse(stream_response(), media_type='text/event-stream')

# .env 파일을 다시 로드하여 새 API 키 적용
@app.post("/reload-api-key")
async def reload_api_key():
    try:
        global settings
        settings = Settings()  
        return {
            "success": True
        }
    
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"API key 로드 실패: {e}")