package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.ServerInstance;

import java.util.List;
import java.util.stream.Collectors;

public record FindCloudInfoRes(
        List<CloudInfoDTO> clouds,
        CloudInfoDTO selectedCloud
) {
    public static FindCloudInfoRes from(List<ServerInstance> serverInstances, ServerInstance selectedServerInstance) {
        List<CloudInfoDTO> cloudInfoResList = serverInstances.stream()
                .map(CloudInfoDTO::from)
                .collect(Collectors.toList());

        CloudInfoDTO selectedCloud = CloudInfoDTO.from(selectedServerInstance);

        return new FindCloudInfoRes(cloudInfoResList, selectedCloud);
    }
}
