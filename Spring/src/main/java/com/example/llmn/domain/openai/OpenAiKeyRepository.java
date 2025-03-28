package com.example.llmn.domain.openai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OpenAiKeyRepository extends JpaRepository<OpenAiKey, Long> {

    Optional<OpenAiKey> findByTempIdentifier(String tempIdentifier);

    Optional<OpenAiKey> findByUserId(Long userId);
}