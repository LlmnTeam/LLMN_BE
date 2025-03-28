package com.example.llmn.domain.openai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OpenAiKeyRepository extends JpaRepository<OpenAiKey, Long> {

    @Query("SELECT o FROM OpenAiKey o WHERE o.user.email = :email")
    Optional<OpenAiKey> findByEmail(String email);

    Optional<OpenAiKey> findByUserId(Long userId);
}