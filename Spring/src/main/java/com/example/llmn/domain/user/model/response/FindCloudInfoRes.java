package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.SshInfo;

import java.util.List;
import java.util.stream.Collectors;

public record FindCloudInfoRes(
        List<CloudInfoDTO> clouds,
        CloudInfoDTO selectedCloud
) {
    public static FindCloudInfoRes from(List<SshInfo> sshInfos, SshInfo selectedSshInfo) {
        List<CloudInfoDTO> cloudInfoResList = sshInfos.stream()
                .map(CloudInfoDTO::from)
                .collect(Collectors.toList());

        CloudInfoDTO selectedCloud = CloudInfoDTO.from(selectedSshInfo);

        return new FindCloudInfoRes(cloudInfoResList, selectedCloud);
    }
}
