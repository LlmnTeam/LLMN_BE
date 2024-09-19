package com.example.llmn.controller.DTO;

public class UserResponse {

    public record LoginDTO(String accessToken) {}

    public record CheckEmailExistDTO(boolean isValid) {}
}
