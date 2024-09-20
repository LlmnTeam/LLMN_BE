# main.py
from fastapi import FastAPI
from pydantic import BaseModel
from pydantic_settings import BaseSettings
import logging
import os
from langchain_openai import ChatOpenAI
from langchain.prompts import PromptTemplate 

app = FastAPI()

# 환경 변수 설정
class Settings(BaseSettings):
    OPENAI_API_KEY: str

    class Config:
        env_file = os.path.join(os.path.dirname(__file__), ".env")

settings = Settings()

# 로깅 설정
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class LogRequest(BaseModel):
    cotent: str

# LLM 요약을 생성하는 함수
async def generate_log_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system log analyst. Your task is to summarize and detect anomalies in the following system logs.\n"
        "Your responses should be in Korean and follow a structured format.\n"
        "Make sure to be concise but provide enough detail for an administrator to understand key events and anomalies.\n"
        "If there are fewer than three events or anomalies, only provide the relevant information. Do not force the list to reach three items.\n"
        "Use icons or emojis to enhance readability and highlight key sections.\n"
        "\n"
        "### Log Data ###\n"
        f"{content}\n"
        "\n"
        "### General Summary ###\n"
        "1. Summarize the key events from the log data.\n"
        "2. The summary should include the main [ERROR], [WARN], and [INFO] events.\n"
        "3. Format the response in the following structure with icons or emojis to improve readability:\n"
        "\n"
        "[일반적인 요약]\n"
        "- 주요 이벤트\n"
        "   1. [First major event]\n"
        "   2. [Second major event]\n"
        "   3. [Third major event]\n"
        "   ...(continue numbering as needed)\n"
        "- 발생 빈도:\n"
        "   - ERROR: [number of ERRORs]\n"
        "   - WARN: [number of WARNs]\n"
        "   - INFO: [number of INFOs]\n"
        "\n"
        "### Anomaly Detection ###\n"
        "1. Identify any abnormal patterns, such as repetitive errors or unusual occurrences.\n"
        "2. Format the response in the following structure with icons or emojis to improve readability:\n"
        "\n"
        "[이상 탐지 요약]\n"
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

    return general_summary, anomaly_summary

async def generate_performance_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance analyst. Your task is to summarize and identify abnormal patterns in the following performance metrics.\n"
        "Your responses should be in Korean and follow a structured format.\n"
        "Make sure to be concise but provide enough detail for an administrator to understand key events and anomalies.\n"
        "If there are fewer than three events or anomalies, only provide the relevant information. Do not force the list to reach three items.\n"
        "Use icons or emojis to enhance readability and highlight key sections.\n"
        "Only include critical and urgent recommendations in the response.\n"
        "\n"
        "### Performance Data ###\n"
        f"{content}\n"
        "\n"
        "### Performance Summary ###\n"
        "1. Format the response in the following structure with icons or emojis to improve readability:\n"
        "\n"
        "[성능 요약]\n"
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
        "Your responses should be in Korean and provide only the most critical and urgent information.\n"
        "The report should focus on key events, abnormal patterns, and immediate actions required to resolve issues.\n"
        "Use icons or emojis to highlight key sections and ensure that the report is structured for quick review by an administrator.\n"
        "If there are fewer than three events or anomalies, only provide the relevant information. Do not force the list to reach three items.\n"
        "Do not include non-critical information. Focus on events or patterns that require immediate attention.\n"
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

# FastAPI 엔드포인트
@app.post("/process/logSummary")
async def process_log_summary(request: LogRequest):
    general_summary, anomaly_summary = await generate_log_summary(request.cotent)
    
    # 결과를 JSON으로 반환
    return {
        "generalSummary": general_summary,
        "anomalySummary": anomaly_summary
    }

@app.post("/process/performanceSummary")
async def process_performance_summary(request: LogRequest):
    performance_summary = await generate_performance_summary(request.cotent)
    
    return {
        "performanceSummary": performance_summary
    }

@app.post("/process/dailySummary")
async def process_daily_summary(request: LogRequest):
    daily_summary = await generate_performance_summary(request.cotent)
    
    return {
        "dailySummaryy": daily_summary
    }