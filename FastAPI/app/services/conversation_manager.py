from app.settings import r, encoding, logger, app_settings
from langchain_openai import ChatOpenAI

class ConversationManager:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.key = f"conversation:{self.user_id}"
        self.summary_key = f"summary:{self.user_id}"
        self.max_token_length = 70000  # 최대 토큰 수
        self.keep_recent_messages = 15  # 요약 후 유지할 최근 대화 수

    # 대화 히스토리에 사용자 입력과 시스템 응답을 추가
    def add_to_history(self, user_input: str, system_response: str, api_key: str):
        r.rpush(self.key, f"User: {user_input}", f"System: {system_response}")
        r.expire(self.key, 1800)  

        # 대화 토큰 수 체크
        if self.calculate_total_tokens() > self.max_token_length:
            self.summarize_conversation(api_key)

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
    def summarize_conversation(self, api_key: str):
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
            openai_api_key=api_key
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
