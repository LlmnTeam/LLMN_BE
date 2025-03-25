package com.example.llmn.domain.user.model;

import com.example.llmn.domain.metric.MetricResponse;
import com.example.llmn.domain.metric.model.response.CpuMetricRes;
import com.example.llmn.domain.metric.model.response.MemoryMetricRes;
import com.example.llmn.domain.metric.model.response.NetworkInMetricRes;
import com.example.llmn.domain.metric.model.response.NetworkOutMetricRes;

import java.util.List;

public class UserResponse {

    public record LoginDTO(String accessToken) {}

    public record CheckEmailExistDTO(boolean isValid) {}

    public record CheckNickNameDTO(boolean isDuplicate) {}

    public record FindDashboardDTO(
            String ip,
            String cpuUsage,
            String memoryUsage,
            String networkReceived,
            String networkSent,
            String summary,
            List<CpuMetricRes> cpuHistory,
            List<MemoryMetricRes> memoryHistory,
            List<NetworkInMetricRes> networkInHistory,
            List<NetworkOutMetricRes> networkOutHistory){}

    public record FindConfigurationInfoDTO(
            String nickName,
            List<SshInfoDTO> sshInfos,
            Long monitoringSshId,
            boolean receivingAlarm){}

    public record SshInfoDTO(
            Long id,
            String remoteName,
            String remoteHost,
            String remoteKeyPath,
            boolean isWorking
    ){}

    public record FindCloudInfoDTO(List<CloudInfoDTO> clouds, CloudInfoDTO selectedCloud){}

    public record CloudInfoDTO(String remoteName, String remoteHost){}

    public record VerifyEmailCodeDTO(boolean isMatching) {}

    public record VerifySshConnectDTO(boolean isValid) {}

    public record RequestApiKeyLoadDTO(boolean success){}

    public record ValidateOpenAIKeyDTO(boolean isValid){}

    public record CheckAccountExistDTO(boolean isValid) {}

    public record EnvUpdateDTO(boolean success){}

    public record ValidateAccessTokenDTO(String nickName){}
}
