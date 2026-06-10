package org.jahia.community.bulkcreateusers.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BulkCreateUsersResult}'s defensive handling of the per-row error list:
 * the result is returned over GraphQL and must not be mutable through the list reference the caller
 * passed in, nor through the accessor.
 */
class BulkCreateUsersResultTest {

    @Test
    @DisplayName("does not reflect mutations made to the source list after construction")
    void isolatedFromSourceList() {
        // Arrange
        List<String> source = new ArrayList<>(Arrays.asList("row 1 failed"));
        BulkCreateUsersResult result = new BulkCreateUsersResult(false, 0, 0, 0, 1, source);

        // Act
        source.add("injected after construction");

        // Assert
        assertThat(result.getErrors()).containsExactly("row 1 failed");
    }

    @Test
    @DisplayName("exposes an unmodifiable error list")
    void errorsAreUnmodifiable() {
        BulkCreateUsersResult result = new BulkCreateUsersResult(false, 0, 0, 0, 1,
                new ArrayList<>(Arrays.asList("boom")));

        assertThatThrownBy(() -> result.getErrors().add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("treats a null error list as an empty list")
    void nullErrorsBecomeEmpty() {
        BulkCreateUsersResult result = new BulkCreateUsersResult(true, 2, 0, 0, 0, null);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("preserves the count fields")
    void preservesCounts() {
        BulkCreateUsersResult result = new BulkCreateUsersResult(true, 3, 2, 1, 0, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCreatedCount()).isEqualTo(3);
        assertThat(result.getUpdatedCount()).isEqualTo(2);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getErrorCount()).isZero();
    }
}
