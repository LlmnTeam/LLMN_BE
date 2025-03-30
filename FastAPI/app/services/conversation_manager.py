# app/services/conversation_manager.py
from typing import Optional, Union
from app.core.config import r, encoding, logger
from langchain_openai import ChatOpenAI

REDIS_EXPIRY_SECONDS = 1800  # 30분
DEFAULT_MAX_TOKEN_LENGTH = 70000
DEFAULT_KEEP_RECENT_MESSAGES = 15
SUMMARY_PREFIX = "Previous Conversation Summary:\n"
USER_MESSAGE_PREFIX = "User: "
SYSTEM_MESSAGE_PREFIX = "System: "
SUMMARIZATION_MODEL = "gpt-4o-mini"
SUMMARIZATION_TEMPERATURE = 0.3
SUMMARIZATION_MAX_TOKENS = 750

class ConversationManager:
    def __init__(
        self, 
        user_id: Union[str, int], 
        max_token_length: int = DEFAULT_MAX_TOKEN_LENGTH,
        keep_recent_messages: int = DEFAULT_KEEP_RECENT_MESSAGES
    ):
        self.user_id = user_id
        self.key = f"conversation:{self.user_id}"
        self.summary_key = f"summary:{self.user_id}"
        self.max_token_length = max_token_length  # 최대 토큰 수
        self.keep_recent_messages = keep_recent_messages  # 요약 후 유지할 최근 대화 수

    def add_messages(self, user_input: str, system_response: str, api_key: str):
        # 사용자 입력과 시스템 응답을 Redis 리스트에 추가
        r.rpush(
            self.key, 
            f"{USER_MESSAGE_PREFIX}{user_input}", 
            f"{SYSTEM_MESSAGE_PREFIX}{system_response}"
        )

        r.expire(self.key, 1800)  

        # 토큰 수가 임계값을 초과하면 대화 요약 실행
        if self.get_total_token_count() > self.max_token_length:
            self.generate_conversation_summary(api_key)

    def get_formatted_conversation(self):
        conversation = []

        try:
            summary = r.get(self.summary_key)
            if summary: # 요약이 존재하면 추가
                conversation.append(f"{SUMMARY_PREFIX}{summary}")
        except Exception as e:
            logger.error(f"대화 요약 조회 중 오류 발생: {str(e)}")

        # 최근 대화 메시지 추가
        recent_messages = self.get_message_list()
        conversation.extend(recent_messages)
        return "\n".join(conversation)
    
    def clear_all_messages(self) -> None:
        try:
            r.delete(self.key)
            r.delete(self.summary_key)
        except Exception as e:
            logger.error(f"대화 기록 삭제 중 오류 발생: {str(e)}")

    # 최근 n개의 대화 히스토리(질문/응답) 가져오기
    def get_message_list(self):
        try:
            return r.lrange(self.key, 0, -1)
        except Exception as e:
            return []

    # 현재 대화 히스토리의 총 토큰 수를 계산
    def get_total_token_count(self):
        total_tokens = 0
        messages = r.lrange(self.key, 0, -1)

        for message in messages:
            total_tokens += ConversationManager.count_tokens(message)
        return total_tokens
    
    # 대화 요약 생성
    def generate_conversation_summary(self, api_key: str) -> None:
        try:
            total_length = r.llen(self.key)
            num_messages_to_summarize = total_length - self.keep_recent_messages * 2
            
            if num_messages_to_summarize <= 0:
                logger.info("요약할 메시지가 없습니다. 요약 수행이 생략됩니다.")
                return
            
            # 요약 대상 메시지 가져오기
            messages_to_summarize = r.lrange(self.key, 0, num_messages_to_summarize - 1)
            conversation_text = "\n".join(messages_to_summarize)
            
            summarization_prompt = self._create_summarization_prompt(conversation_text)
            summary_text = self._generate_summary_text(summarization_prompt, api_key)
            
            if summary_text:
                # 요약 결과 Redis에 저장
                r.set(self.summary_key, summary_text)
                logger.info(f"대화 요약 성공: {num_messages_to_summarize}개의 메시지 요약됨.")
                
                # 요약된 메시지 히스토리에서 제거
                r.ltrim(self.key, num_messages_to_summarize, -1)
        except Exception as e:
            logger.error(f"대화 요약 중 오류 발생: {str(e)}")
    
    def _create_summarization_prompt(self, conversation_text: str) -> str:
        return (
            "Please summarize the following conversation. "
            "Focus on key points, issues, and resolutions.\n\n"
            f"{conversation_text}\n"
            "Summary:"
        )

    def _generate_summary_text(self, prompt: str, api_key: str) -> Optional[str]:
        try:
            summarizer = ChatOpenAI(
                model=SUMMARIZATION_MODEL,
                temperature=SUMMARIZATION_TEMPERATURE,
                max_tokens=SUMMARIZATION_MAX_TOKENS,
                openai_api_key=api_key
            )
            
            summary_response = summarizer.invoke(prompt)
            return summary_response.content.strip()
        except Exception as e:
            logger.error(f"요약 생성 중 오류 발생: {str(e)}")
            return None
        
    # 텍스트의 토큰 수를 계산
    @staticmethod
    def count_tokens(text: str) -> int:
        return len(encoding.encode(text))