package org.jahia.community.bulkcreateusers.graphql;

import org.jahia.community.bulkcreateusers.users.UsersHandler;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.settings.SettingsBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolver-level unit tests for {@link BulkCreateUsersMutation#importUsers}, covering the
 * upload-size short-circuit (F6), the OSGi service lookup/delegation wiring (F7, re-scoped per
 * the gap list to require the same 3-way static-mock setup as D6 Part B), and the resolver's
 * exception-masking {@code catch (Exception e)} (D6 Part B). {@link UsersHandler} itself is
 * mocked throughout, so this class never touches the orchestration logic covered by
 * {@code UsersHandlerImportTest}.
 *
 * <p>D6 fix note: the primary fix for D6 (a mid-batch exception discarding accumulated
 * counts/errors) now lives inside {@link UsersHandler#importUsers} itself - see
 * {@code UsersHandlerImportTest}'s "D6-JUnit Part A" - which no longer lets a per-row exception
 * escape uncaught. The resolver's own {@code catch (Exception e)} is retained as a last-resort
 * defensive fallback for a genuinely unexpected failure that isn't a per-row error (e.g. a bug
 * unrelated to CSV row processing); {@link #maskesUnderlyingExceptionWithGenericResult()} below
 * now documents that fallback role rather than "the bug". {@link #passesThroughPartialResultUnchanged()}
 * is the resolver-level regression guard proving the fix: a handler result carrying partial
 * success/error counts (the shape {@link UsersHandler} now returns after the fix) passes through
 * the resolver completely unchanged.</p>
 */
class BulkCreateUsersMutationTest {

    private static final String MINIMAL_CSV = "j:nodename,j:password,j:firstName,j:lastName\nuser1,pass1,Alice,Smith";

    /** Stubs SettingsBean.getInstance().getJahiaFileUploadMaxSize() so the size check never rejects. */
    private static void stubNoUploadLimit(MockedStatic<SettingsBean> settingsMock) {
        final SettingsBean settingsBean = mock(SettingsBean.class);
        when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(0L);
        settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);
    }

    /** Stubs the global (siteKey == null) scope-authorization branch to grant access. */
    private static void stubGlobalScopeAuthorized(MockedStatic<JCRSessionFactory> sessionFactoryMock)
            throws RepositoryException {
        final JCRSessionFactory sessionFactory = mock(JCRSessionFactory.class);
        final JCRSessionWrapper userSession = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper rootNode = mock(JCRNodeWrapper.class);
        when(sessionFactory.getCurrentUserSession()).thenReturn(userSession);
        when(userSession.getNode("/")).thenReturn(rootNode);
        when(rootNode.hasPermission("adminUsersBulkCreate")).thenReturn(true);
        sessionFactoryMock.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);
    }

    @Nested
    @DisplayName("F6-JUnit: oversized-payload rejection")
    class UploadSizeLimit {

        @Test
        @DisplayName("rejects a payload exceeding the configured upload size limit before any OSGi lookup")
        void rejectsOversizedPayload() {
            final String csvContent = "x".repeat(200);
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                final SettingsBean settingsBean = mock(SettingsBean.class);
                when(settingsBean.getJahiaFileUploadMaxSize())
                        .thenReturn((long) csvContent.getBytes(StandardCharsets.UTF_8).length - 1);
                settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);

                final BulkCreateUsersResult result =
                        new BulkCreateUsersMutation().importUsers(csvContent, ",", null, null, false);

                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getErrorCount()).isEqualTo(1);
                assertThat(result.getErrors())
                        .containsExactly("CSV payload exceeds the configured upload size limit");
                // The size check must short-circuit before ever resolving the OSGi service.
                bundleUtilsMock.verifyNoInteractions();
            }
        }
    }

    @Nested
    @DisplayName("Bug 2 - JUnit: effectiveMaxUploadSize() clamps to the real GraphQL transport ceiling")
    class EffectiveMaxUploadSize {

        @Test
        @DisplayName("clamps a configured limit larger than the transport ceiling down to the ceiling")
        void clampsConfiguredLimitLargerThanCeiling() {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class)) {
                final SettingsBean settingsBean = mock(SettingsBean.class);
                // Jahia's own real-world default (100 MiB), far above the ~20M-character
                // Jackson/graphql-java-kickstart ceiling this transport can actually deliver.
                when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(104_857_600L);
                settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);

                assertThat(BulkCreateUsersMutation.effectiveMaxUploadSize())
                        .isEqualTo(BulkCreateUsersMutation.GRAPHQL_JSON_VARIABLE_MAX_LENGTH);
            }
        }

        @Test
        @DisplayName("keeps a configured limit smaller than the transport ceiling unchanged")
        void keepsConfiguredLimitSmallerThanCeiling() {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class)) {
                final SettingsBean settingsBean = mock(SettingsBean.class);
                when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(5_000_000L);
                settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);

                assertThat(BulkCreateUsersMutation.effectiveMaxUploadSize()).isEqualTo(5_000_000L);
            }
        }

        @Test
        @DisplayName("falls back to the transport ceiling when no limit is configured (0 means unlimited)")
        void fallsBackToCeilingWhenUnconfigured() {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class)) {
                final SettingsBean settingsBean = mock(SettingsBean.class);
                when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(0L);
                settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);

                // An "unlimited" configured setting must not translate into an unenforced check -
                // the transport genuinely cannot deliver more than ~20M characters regardless of
                // operator configuration, so the ceiling still applies.
                assertThat(BulkCreateUsersMutation.effectiveMaxUploadSize())
                        .isEqualTo(BulkCreateUsersMutation.GRAPHQL_JSON_VARIABLE_MAX_LENGTH);
            }
        }
    }

    @Nested
    @DisplayName("F7-JUnit (re-scoped): OSGi service lookup and delegation")
    class OsgiServiceDelegation {

        @Test
        @DisplayName("returns a generic 'Service unavailable' result when the OSGi service cannot be resolved")
        void returnsServiceUnavailableWhenHandlerMissing() throws RepositoryException {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<JCRSessionFactory> sessionFactoryMock = mockStatic(JCRSessionFactory.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                stubNoUploadLimit(settingsMock);
                stubGlobalScopeAuthorized(sessionFactoryMock);
                bundleUtilsMock.when(() -> BundleUtils.getOsgiService(UsersHandler.class, null)).thenReturn(null);

                final BulkCreateUsersResult result =
                        new BulkCreateUsersMutation().importUsers(MINIMAL_CSV, ",", null, null, false);

                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getErrorCount()).isEqualTo(1);
                assertThat(result.getErrors()).containsExactly("Service unavailable");
            }
        }

        @Test
        @DisplayName("delegates to the resolved handler and returns its result unchanged")
        void delegatesToResolvedHandler() throws RepositoryException {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<JCRSessionFactory> sessionFactoryMock = mockStatic(JCRSessionFactory.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                stubNoUploadLimit(settingsMock);
                stubGlobalScopeAuthorized(sessionFactoryMock);
                final UsersHandler handler = mock(UsersHandler.class);
                final BulkCreateUsersResult canned =
                        new BulkCreateUsersResult(true, 1, 0, 0, 0, Collections.emptyList());
                when(handler.importUsers(any(), any(), any(), any(), any(Boolean.class))).thenReturn(canned);
                bundleUtilsMock.when(() -> BundleUtils.getOsgiService(UsersHandler.class, null)).thenReturn(handler);

                final BulkCreateUsersResult result =
                        new BulkCreateUsersMutation().importUsers(MINIMAL_CSV, ",", null, null, false);

                assertThat(result).isSameAs(canned);
                verify(handler).importUsers(MINIMAL_CSV, ",", null, null, false);
            }
        }
    }

    @Nested
    @DisplayName("D6-JUnit Part B (fixed): resolver preserves partial results; masking is now only a last-resort fallback")
    class ExceptionMasking {

        @Test
        @DisplayName("a handler result with partial success/error counts passes through unchanged (D6 fix verification)")
        void passesThroughPartialResultUnchanged() throws RepositoryException {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<JCRSessionFactory> sessionFactoryMock = mockStatic(JCRSessionFactory.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                stubNoUploadLimit(settingsMock);
                stubGlobalScopeAuthorized(sessionFactoryMock);
                final UsersHandler handler = mock(UsersHandler.class);
                // Shape UsersHandler now returns after the D6 fix: earlier rows created (not
                // wiped out) plus one real row-level error message, instead of a fabricated
                // "Internal error" result with every count zeroed.
                final BulkCreateUsersResult partial = new BulkCreateUsersResult(false, 2, 0, 0, 1,
                        Collections.singletonList("Row for 'user2': failed to process due to an unexpected error"));
                when(handler.importUsers(any(), any(), any(), any(), any(Boolean.class))).thenReturn(partial);
                bundleUtilsMock.when(() -> BundleUtils.getOsgiService(UsersHandler.class, null)).thenReturn(handler);

                final BulkCreateUsersResult result =
                        new BulkCreateUsersMutation().importUsers(MINIMAL_CSV, ",", null, null, false);

                assertThat(result).isSameAs(partial);
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getCreatedCount()).isEqualTo(2);
                assertThat(result.getErrorCount()).isEqualTo(1);
                assertThat(result.getErrors()).containsExactly("Row for 'user2': failed to process due to an unexpected error");
            }
        }

        @Test
        @DisplayName("a RuntimeException from handler.importUsers (genuinely unexpected, not per-row) is still caught as a last-resort fallback")
        void maskesUnderlyingExceptionWithGenericResult() throws RepositoryException {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<JCRSessionFactory> sessionFactoryMock = mockStatic(JCRSessionFactory.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                stubNoUploadLimit(settingsMock);
                stubGlobalScopeAuthorized(sessionFactoryMock);
                final UsersHandler handler = mock(UsersHandler.class);
                when(handler.importUsers(any(), any(), any(), any(), any(Boolean.class)))
                        .thenThrow(new RuntimeException("simulated catastrophic failure unrelated to row processing"));
                bundleUtilsMock.when(() -> BundleUtils.getOsgiService(UsersHandler.class, null)).thenReturn(handler);

                final BulkCreateUsersResult result =
                        new BulkCreateUsersMutation().importUsers(MINIMAL_CSV, ",", null, null, false);

                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getCreatedCount()).isZero();
                assertThat(result.getUpdatedCount()).isZero();
                assertThat(result.getSkippedCount()).isZero();
                assertThat(result.getErrorCount()).isEqualTo(1);
                assertThat(result.getErrors()).containsExactly("Internal error during bulk user import");
            }
        }

        @Test
        @DisplayName("never delegates to the resolved handler when the caller is not authorized for the requested scope")
        void doesNotDelegateWhenScopeAuthorizationFails() throws RepositoryException {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<JCRSessionFactory> sessionFactoryMock = mockStatic(JCRSessionFactory.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                stubNoUploadLimit(settingsMock);
                final JCRSessionFactory sessionFactory = mock(JCRSessionFactory.class);
                final JCRSessionWrapper userSession = mock(JCRSessionWrapper.class);
                final JCRNodeWrapper rootNode = mock(JCRNodeWrapper.class);
                when(sessionFactory.getCurrentUserSession()).thenReturn(userSession);
                when(userSession.getNode("/")).thenReturn(rootNode);
                when(rootNode.hasPermission("adminUsersBulkCreate")).thenReturn(false);
                sessionFactoryMock.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);
                // The OSGi lookup happens before the scope re-check, so a resolvable handler must
                // be stubbed here too - the point of this test is that it is never *delegated to*.
                final UsersHandler handler = mock(UsersHandler.class);
                bundleUtilsMock.when(() -> BundleUtils.getOsgiService(UsersHandler.class, null)).thenReturn(handler);

                final BulkCreateUsersResult result =
                        new BulkCreateUsersMutation().importUsers(MINIMAL_CSV, ",", null, null, false);

                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getErrors())
                        .containsExactly("Not authorized to manage users in the requested scope");
                verify(handler, never()).importUsers(any(), any(), any(), any(), any(Boolean.class));
            }
        }
    }
}
