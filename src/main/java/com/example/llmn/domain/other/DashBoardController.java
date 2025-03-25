package com.example.llmn.domain.other;

import com.example.llmn.domain.user.model.UserResponse;
import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;
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
