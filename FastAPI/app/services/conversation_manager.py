# app/services/conversation_manager.py
from typing import Optional, Union
from app.core.config import r, encoding, logger
from langchain_openai import ChatOpenAI
from app.core.conversation_config import conversation_settings as cs

class ConversationManager:
    def __init__(
        self, 
        user_id: Union[str, int], 
        max_token_length: int = cs.DEFAULT_MAX_TOKEN_LENGTH,
        keep_recent_messages: int = cs.DEFAULT_KEEP_RECENT_MESSAGES
    ):
        self.user_id = user_id
        self.conversation_key = f"conversation:{self.user_id}" # 최근 대화 메시지를 원본 그대로 저장하는 레디스 키
        self.summary_key = f"summary:{self.user_id}" # 오래된 대화의 요약본을 저장하는 레디스 키
        self.max_token_length = max_token_length 
        self.keep_recent_messages = keep_recent_messages

    # 대화 내용 추가 및 필요시 요약 실행
    def add_messages(self, user_input: str, system_response: str, api_key: str):
        # 새 메시지는 conversation_key에 추가
        r.rpush(
            self.conversation_key, 
            f"{cs.USER_MESSAGE_PREFIX}{user_input}", 
            f"{cs.SYSTEM_MESSAGE_PREFIX}{system_response}"
        )
        r.expire(self.conversation_key, cs.REDIS_EXPIRY_SECONDS)  

        # 토큰 수 초과시 오래된 메시지를 요약하고 삭제
        if self._calculate_total_token_count() > self.max_token_length:
            self._generate_conversation_summary(api_key)

    # 전체 대화 내용(요약 + 최근 메시지) 형식화하여 반환
    def get_formatted_conversation(self):
        conversation = []

        # 1. summary_key에서 이전 대화 요약 가져오기
        try:
            summary = r.get(self.summary_key)
            if summary:
                conversation.append(f"{cs.SUMMARY_PREFIX}{summary}")
        except Exception as e:
            logger.error(f"대화 요약 조회 중 오류 발생: {str(e)}")

        # 2. conversation_key에서 최근 대화 내용 가져와 합치기
        recent_messages = self._retrieve_message_history()
        conversation.extend(recent_messages)
        return "\n".join(conversation)
    
    # 모든 대화 기록 삭제
    def clear_all_messages(self) -> None:
        try:
            r.delete(self.conversation_key)
            r.delete(self.summary_key)
        except Exception as e:
            logger.error(f"대화 기록 삭제 중 오류 발생: {str(e)}")

    # 현재 저장된 대화의 총 토큰 수 계산
    def _calculate_total_token_count(self):
        total_tokens = 0
        messages = r.lrange(self.conversation_key, 0, -1)

        for message in messages:
            total_tokens += len(encoding.encode(message))
        return total_tokens
    
    # 이전 대화를 요약하고 최근 메시지만 유지 (for 메모리 관리)
    def _generate_conversation_summary(self, api_key: str) -> None:
        try:
            # 요약할 메시지 수 계산
            total_length = r.llen(self.conversation_key)
            num_messages_to_summarize = total_length - self.keep_recent_messages * 2
            
            if num_messages_to_summarize <= 0:
                return
            
            # 오래된 메시지 추출
            messages_to_summarize = r.lrange(self.conversation_key, 0, num_messages_to_summarize - 1)
            conversation_text = "\n".join(messages_to_summarize)
            
            # 요약 생성
            summarization_prompt = self._create_summarization_prompt(conversation_text)
            summary_text = self._generate_summary_text(summarization_prompt, api_key)
            
            if summary_text:
                # 1. 오래된 메시지의 요약을 summary_key에 저장 (컨텍스트 보존)
                r.set(self.summary_key, summary_text)

                # 2. 이미 요약된 오래된 메시지는 conversation_key에서 제거 (메모리 확보)
                r.ltrim(self.conversation_key, num_messages_to_summarize, -1)
        except Exception as e:
            logger.error(f"대화 요약 중 오류 발생: {str(e)}")
    
    # 레디스에서 원본 대화 히스토리 조회
    def _retrieve_message_history(self):
        try:
            return r.lrange(self.conversation_key, 0, -1)
        except Exception as e:
            return []

    def _create_summarization_prompt(self, conversation_text: str) -> str:
        return (
            "Please summarize the following conversation. "
            "Focus on key points, issues, and resolutions.\n\n"
            f"{conversation_text}\n"
            "Summary:"
        )

    # 요약 생성을 위한 프롬프트 생성
    def _generate_summary_text(self, prompt: str, api_key: str) -> Optional[str]:
        try:
            summarizer = ChatOpenAI(
                model=cs.LLM_MODEL_NAME,
                temperature=cs.SUMMARIZATION_TEMPERATURE,
                max_tokens=cs.SUMMARIZATION_MAX_TOKENS,
                openai_api_key=api_key
            )
            
            summary_response = summarizer.invoke(prompt)
            return summary_response.content.strip()
        except Exception as e:
            logger.error(f"요약 생성 중 오류 발생: {str(e)}")
            return None