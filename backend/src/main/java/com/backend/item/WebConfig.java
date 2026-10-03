package com.backend.item;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Every origin is welcome — the web app, a phone, curl — but not every caller is: the security
 * chain wants a bearer token before anything under {@code /api} or the websocket answers. CORS is
 * wide open because it is not what keeps the feed private.
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
                // no token in the URL means no handshake, not a silent anonymous feed
                .addInterceptors(new FeedHandshakeInterceptor())
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
