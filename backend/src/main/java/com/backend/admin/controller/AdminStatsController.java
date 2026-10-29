package com.backend.admin.controller;

import com.backend.admin.dto.AdminStatsResponse;
import com.backend.admin.service.AdminStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    public AdminStatsController(AdminStatsService adminStatsService) {
        this.adminStatsService = adminStatsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminStatsService.getStats());
    }
}
