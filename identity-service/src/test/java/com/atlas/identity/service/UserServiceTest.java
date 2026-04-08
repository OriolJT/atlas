package com.atlas.identity.service;

import com.atlas.identity.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest {

    private User createUser() {
        return new User(UUID.randomUUID(), "test@example.com", "hashed", "John", "Doe");
    }

    @Test
    void isLocked_whenLockedUntilIsInFuture_returnsTrue() {
        User user = createUser();
        user.incrementFailedLoginAttempts(1, 30);

        assertThat(user.isLocked()).isTrue();
    }

    @Test
    void isLocked_whenLockedUntilIsNull_returnsFalse() {
        User user = createUser();

        assertThat(user.isLocked()).isFalse();
    }

    @Test
    void resetFailedLoginAttempts_clearsAttemptsAndLock() {
        User user = createUser();
        user.incrementFailedLoginAttempts(1, 30);
        assertThat(user.isLocked()).isTrue();
        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);

        user.resetFailedLoginAttempts();

        assertThat(user.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.isLocked()).isFalse();
    }

    @Test
    void incrementFailedLoginAttempts_belowThreshold_doesNotLock() {
        User user = createUser();
        int maxAttempts = 5;

        user.incrementFailedLoginAttempts(maxAttempts, 15);
        user.incrementFailedLoginAttempts(maxAttempts, 15);
        user.incrementFailedLoginAttempts(maxAttempts, 15);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(user.isLocked()).isFalse();
        assertThat(user.getLockedUntil()).isNull();
    }
}
