# app/models/openai_key.py
from sqlalchemy import Column, Integer, String, ForeignKey
from sqlalchemy.ext.declarative import declarative_base

Base = declarative_base()

class OpenAIKey(Base):
    __tablename__ = "openai_key_tb"

    id = Column(Integer, primary_key=True, index=True)
    key_value = Column(String(255), nullable=False)
    user_id = Column(Integer, ForeignKey("user.id"), nullable=True)  # 외래 키 설정
    temp_identifier = Column(String(255), unique=True, nullable=True)