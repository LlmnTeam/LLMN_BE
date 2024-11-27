package com.example.llmn.controller;

import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.security.CustomUserDetails;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.DashBoardService;
import com.example.llmn.service.MetricService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class DashBoardController {

    private final DashBoardService dashBoardService;

    @GetMapping("/home")
    public ResponseEntity<?> findDashboard(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse.FindDashboardDTO responseDTO = dashBoardService.findDashboard(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
