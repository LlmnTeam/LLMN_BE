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

async def generate_trend_summary(content: str):    
    prompt = (
        "### Persona ###\n"
        "You are an expert system performance and application log analyst. Your task is to generate a weekly long-term trend analysis report based on daily key summaries.\n"
        "Your responses should be in Korean and focus on trends, patterns, and projections over the past week. The report should emphasize system performance trends, error/warning patterns, abnormal patterns, and provide predictions based on the analyzed data.\n"
        "The structure should be different from daily reports, focusing more on long-term analysis and trends.\n"
        "Use numbers, trends, and clear insights to present the analysis and predictions.\n"
        "\n"
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
        "dailySummary": daily_summary
    }

@app.post("/process/trendSummary")
async def process_daily_summary(request: LogRequest):
    trend_summary = await generate_performance_summary(request.cotent)
    
    return {
        "trendSummary": trend_summary
    }