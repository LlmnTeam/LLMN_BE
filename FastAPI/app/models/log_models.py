# app/models/log_models.py
from pydantic import BaseModel
from typing import List

class LogRequest(BaseModel):
    content: str
    apiKey: str

class LogFile(BaseModel):
    name: str

class LogFilesRequest(BaseModel):
    logFiles: List[LogFile]
    question: str
    isFirstQuestion: bool