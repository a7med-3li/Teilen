package com.backend.item;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * No auth yet, so every origin (the web app, the phone, curl) is welcome.
 */
@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer, WebMvcConfigurer {

    private final ShareItemWebSocketHandler shareItemWebSocketHandler;
    private final String[] allowedOriginPatterns;

    public WebConfig(ShareItemWebSocketHandler shareItemWebSocketHandler,
                     @Value("${teilen.allowed-origin-patterns:*}") String[] allowedOriginPatterns) {
        this.shareItemWebSocketHandler = shareItemWebSocketHandler;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(shareItemWebSocketHandler, "/ws")
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
