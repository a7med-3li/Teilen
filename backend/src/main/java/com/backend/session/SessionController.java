package com.backend.session;

import com.backend.auth.domain.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<SessionDto> listSessions(@AuthenticationPrincipal AuthenticatedUser caller) {
        // Ensure personal session exists
        sessionService.getOrCreatePersonalSession(caller.userId());
        return sessionService.listSessions(caller.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto createSession(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @Valid @RequestBody CreateSessionRequest request) {
        return sessionService.createSession(caller.userId(), request);
    }

    @GetMapping("/{id}")
    public SessionDetailDto getSession(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable UUID id) {
        return sessionService.getSession(caller.userId(), id);
    }

    @PostMapping("/{id}/invite")
    public SessionInviteDto invite(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable UUID id) {
        return sessionService.generateInvite(caller.userId(), id);
    }

    @GetMapping("/join/preview")
    public SessionDto previewInvite(@RequestParam String code) {
        return sessionService.previewInvite(code);
    }

    @PostMapping("/join")
    public SessionDto joinSession(@AuthenticationPrincipal AuthenticatedUser caller,
                                 @Valid @RequestBody JoinSessionRequest request) {
        return sessionService.joinSession(caller.userId(), request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AuthenticatedUser caller,
                             @PathVariable UUID id,
                             @PathVariable UUID userId) {
        sessionService.removeMember(caller.userId(), id, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@AuthenticationPrincipal AuthenticatedUser caller,
                              @PathVariable UUID id) {
        sessionService.deleteSession(caller.userId(), id);
    }
}
