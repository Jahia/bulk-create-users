package org.jahia.community.bulkcreateusers.users;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashSet;
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
}
