package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.ServerInstance;
import com.example.llmn.domain.user.User;

import java.util.List;
import java.util.stream.Collectors;

public record FindConfigurationInfoRes(
        String nickName,
        List<SshInfoRes> sshInfos,
        Long monitoringSshId,
        boolean receivingAlarm
) {
    public static FindConfigurationInfoRes from(User user, List<ServerInstance> serverInstances) {
        List<SshInfoRes> sshInfoResList = serverInstances.stream()
                .map(SshInfoRes::from)
                .collect(Collectors.toList());

        return new FindConfigurationInfoRes(
                user.getNickName(),
                sshInfoResList,
                user.getMonitoringSshId(),
                user.isReceivingAlarm()
        );
    }
}
