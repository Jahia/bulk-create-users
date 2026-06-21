package org.jahia.community.bulkcreateusers.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BulkCreateUsersMutation#isValidSiteKey(String)} — the traversal-safe
 * site-key guard. A malformed key here would be composed into the "/sites/&lt;key&gt;" node path used by
 * the scope authorization check, so rejecting path tokens is security-relevant.
 */
class BulkCreateUsersMutationExtensionTest {

    @ParameterizedTest
    @ValueSource(strings = {"digitall", "my-site", "site_01", "ACME", "a"})
    @DisplayName("accepts well-formed site keys")
    void acceptsValidKeys(String key) {
        assertThat(BulkCreateUsersMutation.isValidSiteKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../systemsite", "site/../other", "/sites/x", "a b", "site key", "site!",
            "../../etc/passwd", "x*", "a:b", "site.key"
    })
    @DisplayName("rejects keys containing path, whitespace, or special characters")
    void rejectsTraversalAndSpecials(String key) {
        assertThat(BulkCreateUsersMutation.isValidSiteKey(key)).isFalse();
    }

    @Test
    @DisplayName("rejects null and empty")
    void rejectsNullAndEmpty() {
        assertThat(BulkCreateUsersMutation.isValidSiteKey(null)).isFalse();
        assertThat(BulkCreateUsersMutation.isValidSiteKey("")).isFalse();
    }

    @Test
    @DisplayName("rejects an over-long key")
    void rejectsOverLongKey() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 151; i++) {
            sb.append('a');
        }
        assertThat(BulkCreateUsersMutation.isValidSiteKey(sb.toString())).isFalse();
    }
}
