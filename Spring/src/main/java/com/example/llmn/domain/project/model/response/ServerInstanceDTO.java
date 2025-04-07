package com.example.llmn.domain.project.model.response;

import java.util.List;

public record ServerInstanceDTO(
        String cloudName,
        Long sshInfoId,
        List<ContainerDTO> containers) {
}
