package com.backend.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminViewController {

    @GetMapping("/admin")
    public String admin() {
        return "forward:/admin.html";
    }
}
