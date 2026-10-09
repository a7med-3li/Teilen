package com.backend.push;

import com.backend.auth.domain.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Web push registration. A browser asks for the server's VAPID public key, subscribes with its own
 * push service, and hands the resulting subscription here. Every call is in the caller's account,
 * so a subscription can only ever be filed against the token that made it.
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService service;

    public PushController(PushService service) {
        this.service = service;
    }

    /** Empty key means push is switched off; the browser then simply does not subscribe. */
    @GetMapping("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", service.publicKey());
    }

    @PostMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@AuthenticationPrincipal AuthenticatedUser caller,
                          @Valid @RequestBody PushSubscriptionRequest request) {
        service.subscribe(caller.userId(), request);
    }

    @DeleteMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal AuthenticatedUser caller,
                            @RequestParam("endpoint") String endpoint) {
        service.unsubscribe(caller.userId(), endpoint);
    }
}
