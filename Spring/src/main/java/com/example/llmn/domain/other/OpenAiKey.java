package com.example.llmn.domain.other;

import com.example.llmn.common.entity.BaseEntity;
import com.example.llmn.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "openai_key_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class OpenAiKey extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String keyValue;

    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Builder
    public OpenAiKey(String keyValue, String tempIdentifier, User user) {
        this.keyValue = keyValue;
        this.user = user;
    }

    public void updateKeyValue(String keyValue, User user) {
        this.user = user;
        this.keyValue = keyValue;
    }
}
