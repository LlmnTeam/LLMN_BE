from langchain_openai import ChatOpenAI
from langchain.prompts import PromptTemplate
from app.settings import app_settings
import logging

logger = logging.getLogger(__name__)

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
        openai_api_key=app_settings.OPENAI_API_KEY
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
        openai_api_key=app_settings.OPENAI_API_KEY
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
        openai_api_key=app_settings.OPENAI_API_KEY
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
        openai_api_key=app_settings.OPENAI_API_KEY
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
        openai_api_key=app_settings.OPENAI_API_KEY
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
        openai_api_key=app_settings.OPENAI_API_KEY
    )
    

    prompt_template = PromptTemplate(input_variables=["prompt"], template="{prompt}")
    formatted_prompt = prompt_template.format(prompt=prompt)

    response = chatmodel.invoke(formatted_prompt)
    hourly_summary = response.content

    return hourly_summary