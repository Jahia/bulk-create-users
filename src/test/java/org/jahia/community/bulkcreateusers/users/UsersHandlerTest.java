package org.jahia.community.bulkcreateusers.users;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure, security-critical decision logic of {@link UsersHandler}:
 * the property denylist, the property-name safety check, the writable-column gate, and the
 * privileged-group denylist. These guard a system-session import, so a regression here is a
 * privilege-escalation regression.
 */
class UsersHandlerTest {

    @Nested
    @DisplayName("isPropertyDenied")
    class PropertyDenylist {

        @ParameterizedTest
        @ValueSource(strings = {
                "j:password", "j:nodename", "j:accountLocked", "j:external", "j:externalSource",
                "j:roles", "j:permissions", "jcr:mixinTypes", "jcr:uuid", "j:rolesInGroup", "j:account"
        })
        @DisplayName("denies credential, role, lock, external and jcr/internal properties")
        void deniesDangerousProperties(String key) {
            assertThat(UsersHandler.isPropertyDenied(key)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"j:firstName", "j:lastName", "j:email", "j:organization", "preferredLanguage"})
        @DisplayName("allows benign profile properties")
        void allowsBenignProperties(String key) {
            assertThat(UsersHandler.isPropertyDenied(key)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "J:PASSWORD", "J:Roles", "JCR:uuid", "J:accountLocked", "J:RolesInGroup", "JCR:mixinTypes"
        })
        @DisplayName("denies privileged properties regardless of case (denylist is case-insensitive)")
        void deniesDangerousPropertiesCaseInsensitive(String key) {
            assertThat(UsersHandler.isPropertyDenied(key)).isTrue();
        }
    }

    @Nested
    @DisplayName("isSafePropertyName")
    class PropertyNameSafety {

        @ParameterizedTest
        @ValueSource(strings = {"j:firstName", "lastName", "j:email", "preferredLanguage", "x"})
        @DisplayName("accepts well-formed (optionally namespaced) property names")
        void acceptsWellFormedNames(String key) {
            assertThat(UsersHandler.isSafePropertyName(key)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "j: firstName", "first name", "j:first\nname", "../etc", "j::x", ":x", "1abc", "j:", "a:b:c"
        })
        @DisplayName("rejects names with whitespace, control chars, path tokens, or malformed namespaces")
        void rejectsMalformedNames(String key) {
            assertThat(UsersHandler.isSafePropertyName(key)).isFalse();
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThat(UsersHandler.isSafePropertyName(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("isWritableColumn")
    class WritableColumn {

        private final Set<String> allowed = new HashSet<>(java.util.Arrays.asList("j:firstName", "j:email"));

        @Test
        @DisplayName("writes an allowed, safe, non-denied column")
        void writesAllowedColumn() {
            assertThat(UsersHandler.isWritableColumn("j:firstName", allowed)).isTrue();
        }

        @Test
        @DisplayName("never writes the reserved groups column")
        void rejectsGroupsColumn() {
            assertThat(UsersHandler.isWritableColumn("groups", allowed)).isFalse();
        }

        @Test
        @DisplayName("never writes a denied property even if explicitly allowed")
        void rejectsDeniedEvenWhenAllowed() {
            Set<String> allowDanger = new HashSet<>(java.util.Arrays.asList("j:password", "j:roles"));
            assertThat(UsersHandler.isWritableColumn("j:password", allowDanger)).isFalse();
            assertThat(UsersHandler.isWritableColumn("j:roles", allowDanger)).isFalse();
        }

        @Test
        @DisplayName("never writes an unsafe property name even if explicitly allowed")
        void rejectsUnsafeNameEvenWhenAllowed() {
            Set<String> allowUnsafe = new HashSet<>(Collections.singletonList("bad name"));
            assertThat(UsersHandler.isWritableColumn("bad name", allowUnsafe)).isFalse();
        }

        @Test
        @DisplayName("does not write a column outside the allowlist")
        void rejectsColumnOutsideAllowlist() {
            assertThat(UsersHandler.isWritableColumn("j:lastName", allowed)).isFalse();
        }

        @Test
        @DisplayName("null allowlist falls back to import-all (backward compatible)")
        void nullAllowlistImportsAll() {
            assertThat(UsersHandler.isWritableColumn("j:lastName", null)).isTrue();
        }
    }

    @Nested
    @DisplayName("separatorChar")
    class SeparatorResolution {

        @Test
        @DisplayName("falls back to comma for a null separator")
        void nullFallsBackToComma() {
            assertThat(UsersHandler.separatorChar(null)).isEqualTo(',');
        }

        @Test
        @DisplayName("falls back to comma for an empty separator")
        void emptyFallsBackToComma() {
            assertThat(UsersHandler.separatorChar("")).isEqualTo(',');
        }

        @ParameterizedTest
        @ValueSource(strings = {";", "\t", "|", ","})
        @DisplayName("honours the first character of a non-empty separator")
        void honoursFirstCharacter(String separator) {
            assertThat(UsersHandler.separatorChar(separator)).isEqualTo(separator.charAt(0));
        }

        @Test
        @DisplayName("uses only the first character of a multi-character separator")
        void usesOnlyFirstCharacter() {
            assertThat(UsersHandler.separatorChar(";;")).isEqualTo(';');
        }
    }

    @Nested
    @DisplayName("isGroupDenied")
    class GroupDenylist {

        @ParameterizedTest
        @ValueSource(strings = {
                "administrators", "Administrators", "site-administrators", "system-administrators",
                "compliance-managers", "privileged", "site-privileged", "SITE-PRIVILEGED"
        })
        @DisplayName("denies administrator and Jahia privilege-granting groups (case-insensitive)")
        void deniesPrivilegedGroups(String resolvedName) {
            assertThat(UsersHandler.isGroupDenied(resolvedName)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"editors", "marketing", "users", "site-editors"})
        @DisplayName("allows ordinary groups")
        void allowsOrdinaryGroups(String resolvedName) {
            assertThat(UsersHandler.isGroupDenied(resolvedName)).isFalse();
        }

        @Test
        @DisplayName("null group name is not denied")
        void nullGroupNotDenied() {
            assertThat(UsersHandler.isGroupDenied(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("sanitizeForLog (U6)")
    class LogSanitization {

        @Test
        @DisplayName("replaces CR, LF and tab with underscore to prevent log-line injection")
        void stripsControlCharacters() {
            assertThat(UsersHandler.sanitizeForLog("bad\r\nname\twith\ttabs"))
                    .isEqualTo("bad__name_with_tabs");
        }

        @Test
        @DisplayName("truncates to 200 characters and appends '...' when longer")
        void truncatesLongValues() {
            final String longValue = repeat('a', 250);

            final String sanitized = UsersHandler.sanitizeForLog(longValue);

            assertThat(sanitized).hasSize(200 + 3).endsWith("...").startsWith(repeat('a', 200));
        }

        @Test
        @DisplayName("does not truncate a value exactly at the 200-character limit")
        void doesNotTruncateAtExactLimit() {
            final String exactly200 = repeat('a', 200);

            assertThat(UsersHandler.sanitizeForLog(exactly200)).isEqualTo(exactly200);
        }

        @Test
        @DisplayName("returns null unchanged (null passthrough)")
        void nullPassesThrough() {
            assertThat(UsersHandler.sanitizeForLog(null)).isNull();
        }

        private String repeat(char c, int count) {
            final StringBuilder sb = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                sb.append(c);
            }
            return sb.toString();
        }
    }

    @Nested
    @DisplayName("parseGroupTokens (F3 — GROUP_PATTERN multi-token parsing)")
    class GroupTokenParsing {

        @Test
        @DisplayName("extracts a single bracketed group")
        void extractsSingleGroup() {
            assertThat(UsersHandler.parseGroupTokens("[privileged]")).containsExactly("privileged");
        }

        @Test
        @DisplayName("extracts multiple bracketed groups in order")
        void extractsMultipleGroups() {
            assertThat(UsersHandler.parseGroupTokens("[group1],[group2],[group3]"))
                    .containsExactly("group1", "group2", "group3");
        }

        @Test
        @DisplayName("trims inner whitespace from each token")
        void trimsInnerWhitespace() {
            assertThat(UsersHandler.parseGroupTokens("[ group1 ],[  group2  ]"))
                    .containsExactly("group1", "group2");
        }

        @Test
        @DisplayName("ignores empty brackets")
        void ignoresEmptyBrackets() {
            assertThat(UsersHandler.parseGroupTokens("[],[group1],[]"))
                    .containsExactly("group1");
        }

        @Test
        @DisplayName("ignores brackets containing only whitespace")
        void ignoresWhitespaceOnlyBrackets() {
            assertThat(UsersHandler.parseGroupTokens("[   ],[group1]"))
                    .containsExactly("group1");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("returns an empty list for null or empty input")
        void returnsEmptyListForNullOrEmpty(String groups) {
            assertThat(UsersHandler.parseGroupTokens(groups)).isEmpty();
        }

        @Test
        @DisplayName("returns an empty list for a blank (whitespace-only) input")
        void returnsEmptyListForBlank() {
            assertThat(UsersHandler.parseGroupTokens("   ")).isEmpty();
        }

        @Test
        @DisplayName("ignores text outside of brackets")
        void ignoresTextOutsideBrackets() {
            List<String> tokens = UsersHandler.parseGroupTokens("noise[group1]more noise[group2]");
            assertThat(tokens).containsExactly("group1", "group2");
        }
    }
}
