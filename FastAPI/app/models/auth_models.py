# app/models/auth_models.py
from pydantic import BaseModel

class ValidateAPIRequest(BaseModel):
    apiKey: str