package com.example.llmn.domain.project.model.response;

import java.util.List;

public record CloudInstanceDTO(
        String cloudName,
        Long sshInfoId,
        List<ContainerDTO> containers) {
}
