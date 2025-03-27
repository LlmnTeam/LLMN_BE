import os
from fastapi import Request, HTTPException, status
from jose import JWTError, jwt
from app.core.config import SECRET_KEY, ALGORITHM, logs_dir
from app.schemas.dto import LogFilesRequest
from app.services.conversation_manager import ConversationManager

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