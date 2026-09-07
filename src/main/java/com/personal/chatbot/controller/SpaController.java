package com.personal.chatbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * History-mode fallback for the Vue app bundled under {@code static/}: client routes are served by
 * {@code index.html}. Keep the list in sync with {@code frontend/src/router.ts}. API and actuator
 * paths are untouched.
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/chat", "/knowledge"})
    public String index() {
        return "forward:/index.html";
    }
}
