package org.jahia.community.bulkcreateusers.users;

import org.jahia.community.bulkcreateusers.graphql.BulkCreateUsersResult;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRGroupNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.usermanager.JahiaGroupManagerService;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration-level unit tests for {@link UsersHandler#importUsers}, exercising
 * {@code runImport -> processRows -> processUser -> handleNewUser/handleExistingUser} against
 * mocked {@link JahiaUserManagerService}/{@link JahiaGroupManagerService} and a mocked
 * {@link JCRSessionWrapper} handed back through a {@code mockStatic(JCRTemplate.class)} seam.
 * This is the orchestration path Stage 2 flagged as having zero Java unit test coverage - only
 * the pure static helpers were previously unit-tested (see {@link UsersHandlerTest}).
 */
class UsersHandlerImportTest {

    private static final List<String> REQUIRED_COLUMNS = Arrays.asList("j:firstName", "j:lastName");

    private JahiaUserManagerService userManagerService;
    private JahiaGroupManagerService groupManagerService;
    private JCRSessionWrapper session;
    private UsersHandler handler;

    @BeforeEach
    void setUp() {
        userManagerService = mock(JahiaUserManagerService.class);
        groupManagerService = mock(JahiaGroupManagerService.class);
        session = mock(JCRSessionWrapper.class);
        handler = new UsersHandler();
        handler.setUserManagerService(userManagerService);
        handler.setGroupManagerService(groupManagerService);

        // Default: syntax is valid unless a test explicitly overrides it (U4).
        when(userManagerService.isUsernameSyntaxCorrect(anyString())).thenReturn(true);
    }

    /**
     * Runs {@code handler.importUsers(...)} with {@link JCRTemplate#getInstance()} statically
     * mocked so {@code doExecuteWithSystemSession} synchronously hands the shared mock
     * {@link #session} to the real callback, mirroring the production contract without needing a
     * live Jahia repository.
     */
    private BulkCreateUsersResult runImport(String csv, String separator, String siteKey,
            List<String> selectedColumns, boolean overwrite) throws RepositoryException {
        try (MockedStatic<JCRTemplate> mockedTemplate = mockStatic(JCRTemplate.class)) {
            final JCRTemplate template = mock(JCRTemplate.class);
            mockedTemplate.when(JCRTemplate::getInstance).thenReturn(template);
            when(template.doExecuteWithSystemSession(any())).thenAnswer(invocation -> {
                final JCRCallback<?> callback = invocation.getArgument(0);
                return callback.doInJCR(session);
            });
            return handler.importUsers(csv, separator, siteKey, selectedColumns, overwrite);
        }
    }

    private static JCRUserNode mockUserNode(String username) {
        final JCRUserNode user = mock(JCRUserNode.class);
        when(user.getName()).thenReturn(username);
        return user;
    }

    @Nested
    @DisplayName("F1-JUnit: core 2-row import success path")
    class CoreImportSuccess {

        @Test
        @DisplayName("creates both users from a valid 2-row CSV and reports zero errors")
        void createsUsersFromValidCsv() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\n"
                    + "user1,pass1,Alice,Smith\n"
                    + "user2,pass2,Bob,Jones";
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);
            when(userManagerService.createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session)))
                    .thenAnswer(invocation -> mockUserNode(invocation.getArgument(0)));

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCreatedCount()).isEqualTo(2);
            assertThat(result.getErrorCount()).isZero();
            verify(userManagerService, times(2))
                    .createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session));
            verify(userManagerService).createUser(eq("user1"), isNull(), eq("pass1"), any(Properties.class), eq(session));
            verify(userManagerService).createUser(eq("user2"), isNull(), eq("pass2"), any(Properties.class), eq(session));
        }
    }

    @Nested
    @DisplayName("F4-JUnit: overwrite-mode skip vs update, plus the orphaned duplicate-j:nodename check")
    class OverwriteAndSkip {

        @Test
        @DisplayName("overwrite=false skips an existing user and never calls setProperty")
        void skipsExistingUserWithoutOverwrite() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\nexistingUser,pass1,Alice,Smith";
            final JCRUserNode existing = mockUserNode("existingUser");
            when(userManagerService.lookupUser(eq("existingUser"), isNull(), eq(session))).thenReturn(existing);

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getUpdatedCount()).isZero();
            assertThat(result.getCreatedCount()).isZero();
            verify(existing, never()).setProperty(anyString(), anyString());
        }

        @Test
        @DisplayName("overwrite=true updates each allowed property of an existing, non-protected user")
        void overwritesExistingUser() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\nexistingUser,pass1,Alice,Smith";
            final JCRUserNode existing = mockUserNode("existingUser");
            when(userManagerService.lookupUser(eq("existingUser"), isNull(), eq(session))).thenReturn(existing);
            // groupManagerService.lookupGroup(...) is unstubbed -> returns null -> not protected.

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, true);

            assertThat(result.getUpdatedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getCreatedCount()).isZero();
            verify(existing).setProperty("j:firstName", "Alice");
            verify(existing).setProperty("j:lastName", "Smith");
        }

        @Test
        @DisplayName("a duplicate j:nodename within the same CSV degrades the second row to existing-user semantics")
        void duplicateNodenameInSameCsvIsTreatedAsExisting() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\n"
                    + "dupUser,pass1,Alice,Smith\n"
                    + "dupUser,pass1,AliceAgain,SmithAgain";
            final JCRUserNode createdThenExisting = mockUserNode("dupUser");
            // First occurrence: lookupUser finds nothing (new user). By the time the second
            // occurrence is processed, the row-1 commit has already made it visible, exactly as
            // Stage 2's "Resolved open questions" note describes.
            when(userManagerService.lookupUser(eq("dupUser"), isNull(), eq(session)))
                    .thenReturn(null, createdThenExisting);
            when(userManagerService.createUser(eq("dupUser"), isNull(), anyString(), any(Properties.class), eq(session)))
                    .thenReturn(createdThenExisting);

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.getCreatedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getErrorCount()).isZero();
            verify(userManagerService, times(1))
                    .createUser(eq("dupUser"), isNull(), anyString(), any(Properties.class), eq(session));
        }
    }

    @Nested
    @DisplayName("F2-JUnit: selectedColumns allowlist intersection")
    class ColumnSelection {

        private static final String CSV = "j:nodename,j:password,j:firstName,j:lastName,j:email\n"
                + "user1,pass1,Alice,Smith,alice@example.com";

        @Test
        @DisplayName("an optional column NOT in selectedColumns is absent from the Properties passed to createUser")
        void unselectedOptionalColumnIsExcluded() throws RepositoryException {
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);
            when(userManagerService.createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session)))
                    .thenAnswer(invocation -> mockUserNode(invocation.getArgument(0)));
            final ArgumentCaptor<Properties> propsCaptor = ArgumentCaptor.forClass(Properties.class);

            final BulkCreateUsersResult result = runImport(CSV, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.getCreatedCount()).isEqualTo(1);
            verify(userManagerService).createUser(anyString(), isNull(), anyString(), propsCaptor.capture(), eq(session));
            assertThat(propsCaptor.getValue()).doesNotContainKey("j:email");
        }

        @Test
        @DisplayName("an optional column included in selectedColumns is present with the CSV's value")
        void selectedOptionalColumnIsIncluded() throws RepositoryException {
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);
            when(userManagerService.createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session)))
                    .thenAnswer(invocation -> mockUserNode(invocation.getArgument(0)));
            final ArgumentCaptor<Properties> propsCaptor = ArgumentCaptor.forClass(Properties.class);
            final List<String> withEmail = Arrays.asList("j:firstName", "j:lastName", "j:email");

            final BulkCreateUsersResult result = runImport(CSV, ",", null, withEmail, false);

            assertThat(result.getCreatedCount()).isEqualTo(1);
            verify(userManagerService).createUser(anyString(), isNull(), anyString(), propsCaptor.capture(), eq(session));
            assertThat(propsCaptor.getValue()).containsEntry("j:email", "alice@example.com");
        }
    }

    @Nested
    @DisplayName("U4-JUnit: username syntax validation before user creation")
    class UsernameSyntaxValidation {

        @Test
        @DisplayName("an invalid username is reported as an error and createUser is never invoked")
        void rejectsInvalidUsernameSyntax() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\nbad user,pass1,Alice,Smith";
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);
            when(userManagerService.isUsernameSyntaxCorrect("bad user")).thenReturn(false);

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.getErrorCount()).isEqualTo(1);
            assertThat(result.getCreatedCount()).isZero();
            assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid username syntax"));
            verify(userManagerService, never())
                    .createUser(anyString(), any(), anyString(), any(Properties.class), eq(session));
        }
    }

    @Nested
    @DisplayName("F11-EmptyRequiredValue: an empty required-property cell throws and is reported as a row error")
    class EmptyRequiredValue {

        @Test
        @DisplayName("empty j:firstName throws IllegalArgumentException internally and is surfaced as a row error")
        void emptyFirstNameIsReportedAsError() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\nuser1,pass1,,Smith";
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.getErrorCount()).isEqualTo(1);
            assertThat(result.getCreatedCount()).isZero();
            assertThat(result.getErrors())
                    .anyMatch(e -> e.contains("user1") && e.contains("Empty value for required column: j:firstName"));
            verify(userManagerService, never())
                    .createUser(anyString(), any(), anyString(), any(Properties.class), eq(session));
        }
    }

    @Nested
    @DisplayName("D2-JUnit: broader administrators-group protection (not just literal root)")
    class BroaderAdminProtection {

        @Test
        @DisplayName("a non-root member of the administrators group is skipped on overwrite, not updated")
        void nonRootAdminGroupMemberIsSkippedOnOverwrite() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\nadmin2,pass1,Alice,Smith";
            final JCRUserNode existing = mockUserNode("admin2");
            final JCRGroupNode admins = mock(JCRGroupNode.class);
            when(userManagerService.lookupUser(eq("admin2"), isNull(), eq(session))).thenReturn(existing);
            when(groupManagerService.lookupGroup(isNull(), eq("administrators"), eq(session))).thenReturn(admins);
            when(admins.isMember(any(JCRNodeWrapper.class))).thenReturn(true);

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, true);

            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getUpdatedCount()).isZero();
            verify(existing, never()).setProperty(anyString(), anyString());
        }

        @Test
        @DisplayName("a RuntimeException during the membership check fails closed (account treated as protected)")
        void membershipCheckFailureFailsClosed() throws RepositoryException {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\nsomeUser,pass1,Alice,Smith";
            final JCRUserNode existing = mockUserNode("someUser");
            when(userManagerService.lookupUser(eq("someUser"), isNull(), eq(session))).thenReturn(existing);
            when(groupManagerService.lookupGroup(isNull(), eq("administrators"), eq(session)))
                    .thenThrow(new RuntimeException("membership lookup boom"));

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, true);

            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getUpdatedCount()).isZero();
            verify(existing, never()).setProperty(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("U3-JUnit: MAX_ROWS ceiling")
    class MaxRowsCeiling {

        @Test
        @DisplayName("processing stops at MAX_ROWS and reports the abort error; earlier rows remain committed")
        void abortsAtMaxRowsCeiling() throws Exception {
            final int maxRows = readMaxRows();
            final JCRUserNode sharedUser = mockUserNode("bulk-user");
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);
            when(userManagerService.createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session)))
                    .thenReturn(sharedUser);

            final String csv = buildCsv(maxRows + 1);

            final BulkCreateUsersResult result = runImport(csv, ",", null, REQUIRED_COLUMNS, false);

            assertThat(result.getErrorCount()).isEqualTo(1);
            assertThat(result.getCreatedCount()).isEqualTo(maxRows);
            assertThat(result.getErrors())
                    .containsExactly("Import aborted: exceeded the maximum of " + maxRows + " rows");
            verify(userManagerService, times(maxRows))
                    .createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session));
        }

        private String buildCsv(int rowCount) {
            final StringBuilder sb = new StringBuilder("j:nodename,j:password,j:firstName,j:lastName\n");
            for (int i = 0; i < rowCount; i++) {
                sb.append("user").append(i).append(",pass,First,Last\n");
            }
            return sb.toString();
        }

        private int readMaxRows() throws NoSuchFieldException, IllegalAccessException {
            final Field field = UsersHandler.class.getDeclaredField("MAX_ROWS");
            field.setAccessible(true);
            return (int) field.get(null);
        }
    }

    @Nested
    @DisplayName("D6-JUnit Part A (highest priority): an uncaught exception propagates out of importUsers")
    class UncaughtExceptionPropagation {

        @Test
        @DisplayName("createUser throwing on row 2 propagates uncaught, but row 1 was already committed")
        void uncaughtExceptionPropagatesAfterFirstRowCommits() {
            final String csv = "j:nodename,j:password,j:firstName,j:lastName\n"
                    + "user1,pass1,Alice,Smith\n"
                    + "user2,pass2,Bob,Jones\n"
                    + "user3,pass3,Carol,Doe";
            when(userManagerService.lookupUser(anyString(), isNull(), eq(session))).thenReturn(null);
            // The replacement user mock must be fully constructed (including its own
            // when(...).thenReturn(...) stubbing) BEFORE this outer when(...) chain starts -
            // otherwise Mockito's stubbing-in-progress tracking gets confused by the nested
            // mock interaction and throws UnfinishedStubbingException.
            final JCRUserNode user1 = mockUserNode("user1");
            when(userManagerService.createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session)))
                    .thenReturn(user1)
                    .thenThrow(new RuntimeException("simulated JCR failure"));

            assertThatThrownBy(() -> runImport(csv, ",", null, REQUIRED_COLUMNS, false))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("simulated JCR failure");

            // Row 1 succeeded and was committed (session.save() called once) before row 2 threw;
            // row 3 was never reached (createUser called exactly twice, not three times).
            verify(userManagerService, times(2))
                    .createUser(anyString(), isNull(), anyString(), any(Properties.class), eq(session));
            try {
                verify(session, times(1)).save();
            } catch (RepositoryException e) {
                throw new AssertionError("session.save() mock verification should not throw", e);
            }
        }
    }
}
