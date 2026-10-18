package com.backend.auth.repository;

import com.backend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    default Optional<User> findByPhone(String phone) {
        return findByUsernameIgnoreCase(phone);
    }

    default Optional<User> findByUsername(String username) {
        return findByUsernameIgnoreCase(username);
    }
}
