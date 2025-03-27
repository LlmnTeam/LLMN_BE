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

    @Column(unique = true)
    private String tempIdentifier; // 임시 식별자 (이메일 또는 랜덤 값)

    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Builder
    public OpenAiKey(String keyValue, String tempIdentifier, User user) {
        this.keyValue = keyValue;
        this.tempIdentifier = tempIdentifier;
        this.user = user;
    }

    public void assignToUser(User user) {
        this.user = user;
        this.tempIdentifier = null; // 사용자에게 할당되면 임시 식별자는 더 이상 필요 없음
    }

    public void updateKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }
}
