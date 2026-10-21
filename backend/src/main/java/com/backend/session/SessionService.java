package com.backend.session;

import com.backend.auth.domain.Tokens;
import com.backend.auth.entity.User;
import com.backend.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final SessionMemberRepository memberRepository;
    private final UserRepository userRepository;

    public SessionService(SessionRepository sessionRepository,
                          SessionMemberRepository memberRepository,
                          UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Session getOrCreatePersonalSession(UUID userId) {
        return sessionRepository.findByOwnerUserIdAndIsPersonalTrue(userId)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    Session session = new Session(UUID.randomUUID(), "Personal Vault", userId, null, true, now);
                    sessionRepository.save(session);
                    SessionMember member = new SessionMember(UUID.randomUUID(), session.getId(), userId,
                            SessionRole.OWNER, null, now);
                    memberRepository.save(member);
                    log.info("Created personal session {} for user {}", session.getId(), userId);
                    return session;
                });
    }

    @Transactional(readOnly = true)
    public List<SessionDto> listSessions(UUID userId) {
        List<SessionMember> memberships = memberRepository.findByUserId(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<UUID> sessionIds = memberships.stream().map(SessionMember::getSessionId).toList();
        Map<UUID, Session> sessionMap = sessionRepository.findAllById(sessionIds).stream()
                .collect(Collectors.toMap(Session::getId, Function.identity()));

        List<SessionDto> result = new ArrayList<>();
        for (SessionMember m : memberships) {
            Session s = sessionMap.get(m.getSessionId());
            if (s != null) {
                long count = memberRepository.countBySessionId(s.getId());
                result.add(new SessionDto(
                        s.getId(),
                        s.getName(),
                        s.isPersonal(),
                        m.getRole().name(),
                        count,
                        s.getCreatedAt(),
                        m.getEncryptedSessionKey()
                ));
            }
        }

        // Sort: Personal session first, then by createdAt asc
        result.sort((a, b) -> {
            if (a.isPersonal() && !b.isPersonal()) return -1;
            if (!a.isPersonal() && b.isPersonal()) return 1;
            return a.createdAt().compareTo(b.createdAt());
        });

        return result;
    }

    @Transactional
    public SessionDto createSession(UUID userId, CreateSessionRequest request) {
        String name = request.name().strip();
        Instant now = Instant.now();
        UUID sessionId = UUID.randomUUID();

        // Generate an initial random invite code
        String code = Tokens.newUserCode();
        String codeHash = Tokens.hash(code);

        Session session = new Session(sessionId, name, userId, codeHash, false, now);
        sessionRepository.save(session);

        SessionMember member = new SessionMember(UUID.randomUUID(), sessionId, userId,
                SessionRole.OWNER, request.encryptedSessionKey(), now);
        memberRepository.save(member);

        log.info("User {} created shared session {} ({})", userId, sessionId, name);
        return new SessionDto(sessionId, name, false, SessionRole.OWNER.name(), 1L, now, request.encryptedSessionKey());
    }

    @Transactional(readOnly = true)
    public SessionDetailDto getSession(UUID userId, UUID sessionId) {
        SessionMember callerMember = memberRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this session"));

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        List<SessionMember> members = memberRepository.findBySessionId(sessionId);
        List<UUID> userIds = members.stream().map(SessionMember::getUserId).toList();
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<SessionMemberDto> memberDtos = members.stream().map(m -> {
            User u = users.get(m.getUserId());
            String uname = u != null ? u.getUsername() : "unknown";
            String dname = u != null ? u.getDisplayName() : null;
            return new SessionMemberDto(m.getUserId(), uname, dname, m.getRole().name(), m.getJoinedAt());
        }).toList();

        return new SessionDetailDto(session.getId(), session.getName(), session.isPersonal(),
                callerMember.getRole().name(), memberDtos, session.getCreatedAt());
    }

    @Transactional
    public SessionInviteDto generateInvite(UUID userId, UUID sessionId) {
        memberRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this session"));

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.isPersonal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Personal sessions cannot be shared");
        }

        String rawCode = Tokens.newUserCode();
        String formatted = Tokens.formatUserCode(rawCode);
        session.setInviteCodeHash(Tokens.hash(rawCode));
        sessionRepository.save(session);

        String inviteUri = "teilen://session/join?code=" + formatted;
        return new SessionInviteDto(sessionId, session.getName(), formatted, inviteUri);
    }

    @Transactional(readOnly = true)
    public SessionDto previewInvite(String rawCode) {
        String compact = Tokens.normalizeUserCode(rawCode);
        if (compact.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invite code");
        }
        Session session = sessionRepository.findByInviteCodeHash(Tokens.hash(compact))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session invite not found or expired"));

        long count = memberRepository.countBySessionId(session.getId());
        return new SessionDto(session.getId(), session.getName(), session.isPersonal(), "PREVIEW", count,
                session.getCreatedAt(), null);
    }

    @Transactional
    public SessionDto joinSession(UUID userId, JoinSessionRequest request) {
        String compact = Tokens.normalizeUserCode(request.inviteCode());
        if (compact.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invite code");
        }

        Session session = sessionRepository.findByInviteCodeHash(Tokens.hash(compact))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid invite code"));

        if (session.isPersonal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot join personal sessions");
        }

        SessionMember existing = memberRepository.findBySessionIdAndUserId(session.getId(), userId).orElse(null);
        if (existing != null) {
            long count = memberRepository.countBySessionId(session.getId());
            return new SessionDto(session.getId(), session.getName(), session.isPersonal(), existing.getRole().name(),
                    count, session.getCreatedAt(), existing.getEncryptedSessionKey());
        }

        Instant now = Instant.now();
        SessionMember member = new SessionMember(UUID.randomUUID(), session.getId(), userId,
                SessionRole.MEMBER, request.encryptedSessionKey(), now);
        memberRepository.save(member);

        long count = memberRepository.countBySessionId(session.getId());
        log.info("User {} joined shared session {} ({})", userId, session.getId(), session.getName());
        return new SessionDto(session.getId(), session.getName(), session.isPersonal(), SessionRole.MEMBER.name(),
                count, session.getCreatedAt(), request.encryptedSessionKey());
    }

    @Transactional
    public void removeMember(UUID callerUserId, UUID sessionId, UUID targetUserId) {
        SessionMember caller = memberRepository.findBySessionIdAndUserId(sessionId, callerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this session"));

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.isPersonal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot modify personal session members");
        }

        boolean isSelf = callerUserId.equals(targetUserId);
        boolean isOwner = caller.getRole() == SessionRole.OWNER;

        if (!isSelf && !isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the session owner can remove members");
        }

        if (isSelf && isOwner) {
            long count = memberRepository.countBySessionId(sessionId);
            if (count > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Owner cannot leave while other members exist; delete the session or transfer ownership first");
            }
        }

        memberRepository.deleteBySessionIdAndUserId(sessionId, targetUserId);
        log.info("Member {} removed from session {} by {}", targetUserId, sessionId, callerUserId);
    }

    @Transactional
    public void deleteSession(UUID callerUserId, UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.isPersonal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete personal session");
        }

        if (!Objects.equals(session.getOwnerUserId(), callerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can delete this session");
        }

        sessionRepository.delete(session);
        log.info("Owner {} deleted session {}", callerUserId, sessionId);
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID sessionId, UUID userId) {
        return memberRepository.existsBySessionIdAndUserId(sessionId, userId);
    }

    @Transactional(readOnly = true)
    public List<UUID> getMemberUserIds(UUID sessionId) {
        return memberRepository.findBySessionId(sessionId).stream()
                .map(SessionMember::getUserId)
                .toList();
    }
}
