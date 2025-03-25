package com.example.llmn.domain.alarm;

import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping("/alarms")
    public ResponseEntity<?> findAlarmList(@AuthenticationPrincipal CustomUserDetails userDetails){
        AlarmResponse.FindAlarmListDTO responseDTO = alarmService.findAlarmList(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/alarms/read")
    public ResponseEntity<?> readAlarm(@RequestBody AlarmRequest.ReadAlarmDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails){
        alarmService.readAlarm(requestDTO, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
