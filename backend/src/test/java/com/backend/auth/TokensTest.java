package com.backend.auth;

import com.backend.auth.domain.Tokens;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strings people type and read off screens. They get normalised in one place so a scanned code,
 * a typed code and a stored hash always agree.
 */
class TokensTest {

    @Test
    void phoneNumbersAreReducedToDigitsAndAnOptionalPlus() {
        assertThat(Tokens.normalizePhone("+49 (0)151 23 45 678")).isEqualTo("+4901512345678");
        assertThat(Tokens.normalizePhone("  0151 / 2345678 ")).isEqualTo("01512345678");
        assertThat(Tokens.normalizePhone("+20 100 123 4567")).isEqualTo("+201001234567");
        assertThat(Tokens.normalizePhone(null)).isEmpty();
        assertThat(Tokens.normalizePhone("not a number")).isEmpty();
    }

    @Test
    void userCodesIgnoreHowTheyAreWritten() {
        assertThat(Tokens.normalizeUserCode("k7pm-3xqd")).isEqualTo("K7PM3XQD");
        assertThat(Tokens.normalizeUserCode("K7PM 3XQD")).isEqualTo("K7PM3XQD");
        assertThat(Tokens.normalizeUserCode(" K7PM3XQD ")).isEqualTo("K7PM3XQD");
        assertThat(Tokens.formatUserCode("k7pm3xqd")).isEqualTo("K7PM-3XQD");
        assertThat(Tokens.formatUserCode(" K7PM-3XQD ")).isEqualTo("K7PM-3XQD");
    }

    @Test
    void generatedCodesAvoidTheCharactersPeopleMisread() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            String code = Tokens.newUserCode();
            assertThat(code).hasSize(8);
            assertThat(code).doesNotContain("I", "O", "0", "1");
            codes.add(code);
        }
        // 32^8 possibilities; a few hundred draws must not repeat
        assertThat(codes).hasSize(400);
    }

    @Test
    void tokensAreLongRandomAndHashToSomethingFixed() {
        String token = Tokens.newToken();

        assertThat(token).hasSizeGreaterThan(40).doesNotContain("+", "/", "=");
        assertThat(Tokens.hash(token)).hasSize(64).isEqualTo(Tokens.hash(token));
        assertThat(Tokens.hash(token)).isNotEqualTo(Tokens.hash(Tokens.newToken()));
        assertThat(Tokens.hash(null)).isNotNull();
    }
}
