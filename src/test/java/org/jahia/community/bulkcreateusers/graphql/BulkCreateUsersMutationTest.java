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
    @DisplayName("D6-JUnit Part B (highest priority): the resolver masks a propagated exception")
    class ExceptionMasking {

        @Test
        @DisplayName("a RuntimeException from handler.importUsers is caught and turned into a generic error result")
        void maskesUnderlyingExceptionWithGenericResult() throws RepositoryException {
            try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class);
                 MockedStatic<JCRSessionFactory> sessionFactoryMock = mockStatic(JCRSessionFactory.class);
                 MockedStatic<BundleUtils> bundleUtilsMock = mockStatic(BundleUtils.class)) {
                stubNoUploadLimit(settingsMock);
                stubGlobalScopeAuthorized(sessionFactoryMock);
                final UsersHandler handler = mock(UsersHandler.class);
                when(handler.importUsers(any(), any(), any(), any(), any(Boolean.class)))
                        .thenThrow(new RuntimeException("simulated JCR failure"));
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
