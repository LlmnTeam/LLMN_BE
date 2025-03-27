package com.example.llmn.domain.user;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.user.model.request.JoinReq;
import com.example.llmn.domain.user.model.request.SshInfoReq;
import com.example.llmn.domain.user.model.request.UpdateConfigurationReq;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.llmn.common.constants.GlobalConstants.PREFIX_CODE;

@Component
@RequiredArgsConstructor
public class UserValidator {

    private final UserRepository userRepository;
    private final RedisService redisService;

    public void validateJoinRequest(JoinReq requestDTO) {
        validatePasswordsMatch(requestDTO);
        validateSshInfosNotEmpty(requestDTO.sshInfos());
        validateSshHostsUnique(requestDTO.sshInfos());
        validateEmailNotExists(requestDTO.email());
        validateNicknameNotExists(requestDTO.nickName());
    }

    @Async
    public void validateAlreadySendCode(String email, String codeType) {
        if (redisService.isValueExist(getCodeTypeKey(codeType), email)) {
            throw new CustomException(ExceptionCode.ALREADY_SEND_EMAIL);
        }
    }

    public void validateMonitoringSshHostSelected(UpdateConfigurationReq requestDTO) {
        if (requestDTO.sshInfos().stream()
                .noneMatch(sshInfoReq -> sshInfoReq.remoteHost().equals(requestDTO.monitoringSshHost()))) {
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);
        }
    }

    private void validatePasswordsMatch(JoinReq requestDTO) {
        if (!requestDTO.password().equals(requestDTO.passwordConfirm())) {
            throw new CustomException(ExceptionCode.USER_PASSWORD_WRONG);
        }
    }

    public void validateSshInfosNotEmpty(List<SshInfoReq> sshInfoReqs) {
        if (sshInfoReqs.isEmpty()) {
            throw new CustomException(ExceptionCode.SSH_INFO_EMPTY);
        }
    }

    public void validateSshHostsUnique(List<SshInfoReq> sshInfos) {
        Set<String> remoteHostSet = sshInfos.stream()
                .map(SshInfoReq::remoteHost)
                .collect(Collectors.toSet());

        if (remoteHostSet.size() < sshInfos.size()) {
            throw new CustomException(ExceptionCode.DUPLICATE_SSH_HOST);
        }
    }

    private void validateEmailNotExists(String email) {
        if (userRepository.existsByEmail(email))
            throw new CustomException(ExceptionCode.USER_EMAIL_EXIST);
    }

    private void validateNicknameNotExists(String nickName) {
        if (userRepository.existsByNickname(nickName))
            throw new CustomException(ExceptionCode.USER_NICKNAME_EXIST);
    }

    private String getCodeTypeKey(String codeType) {
        return PREFIX_CODE + codeType;
    }
}
