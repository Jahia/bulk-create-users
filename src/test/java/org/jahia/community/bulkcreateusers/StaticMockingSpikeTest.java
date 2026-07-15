package org.jahia.community.bulkcreateusers;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.settings.SettingsBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.jcr.RepositoryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Phase 0 spike (gap list item #0): proves {@code Mockito.mockStatic(...)} genuinely works
 * against the real Jahia core classes/JARs on this module's test classpath — not just that it
 * compiles, but that {@code mvn test} runs it green — for every static singleton the Phase
 * 2/3 specs depend on: {@link JCRTemplate}, {@link SettingsBean}, {@link JCRSessionFactory},
 * and {@link BundleUtils}. This is throwaway scaffolding for the seam, not a behavioral spec of
 * production logic; the real behavioral coverage lives in {@code UsersHandlerImportTest} and
 * {@code BulkCreateUsersMutationTest}.
 */
class StaticMockingSpikeTest {

    @Test
    @DisplayName("mockStatic(JCRTemplate.class) intercepts getInstance() and a doAnswer invokes the JCRCallback synchronously")
    void jcrTemplateStaticMockWorks() throws RepositoryException {
        final JCRTemplate template = Mockito.mock(JCRTemplate.class);
        final JCRSessionWrapper session = Mockito.mock(JCRSessionWrapper.class);

        try (MockedStatic<JCRTemplate> mocked = mockStatic(JCRTemplate.class)) {
            mocked.when(JCRTemplate::getInstance).thenReturn(template);
            when(template.doExecuteWithSystemSession(any())).thenAnswer(invocation -> {
                final JCRCallback<?> callback = invocation.getArgument(0);
                return callback.doInJCR(session);
            });

            final Object result = JCRTemplate.getInstance().doExecuteWithSystemSession(s -> {
                assertThat(s).isSameAs(session);
                return "spike-ok";
            });

            assertThat(result).isEqualTo("spike-ok");
        }
    }

    @Test
    @DisplayName("mockStatic(SettingsBean.class) intercepts getInstance()")
    void settingsBeanStaticMockWorks() {
        final SettingsBean settingsBean = Mockito.mock(SettingsBean.class);
        try (MockedStatic<SettingsBean> mocked = mockStatic(SettingsBean.class)) {
            mocked.when(SettingsBean::getInstance).thenReturn(settingsBean);
            when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(123L);

            assertThat(SettingsBean.getInstance().getJahiaFileUploadMaxSize()).isEqualTo(123L);
        }
    }

    @Test
    @DisplayName("mockStatic(JCRSessionFactory.class) intercepts getInstance() and the returned session's node permission check")
    void jcrSessionFactoryStaticMockWorks() throws RepositoryException {
        final JCRSessionFactory sessionFactory = Mockito.mock(JCRSessionFactory.class);
        final JCRSessionWrapper userSession = Mockito.mock(JCRSessionWrapper.class);
        final JCRNodeWrapper rootNode = Mockito.mock(JCRNodeWrapper.class);

        try (MockedStatic<JCRSessionFactory> mocked = mockStatic(JCRSessionFactory.class)) {
            mocked.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);
            when(sessionFactory.getCurrentUserSession()).thenReturn(userSession);
            when(userSession.getNode("/")).thenReturn(rootNode);
            when(rootNode.hasPermission("adminUsersBulkCreate")).thenReturn(true);

            final boolean authorized = JCRSessionFactory.getInstance().getCurrentUserSession()
                    .getNode("/").hasPermission("adminUsersBulkCreate");

            assertThat(authorized).isTrue();
        }
    }

    @Test
    @DisplayName("mockStatic(BundleUtils.class) intercepts the final utility class's static getOsgiService(...)")
    void bundleUtilsStaticMockWorks() {
        final Object fakeService = new Object();
        try (MockedStatic<BundleUtils> mocked = mockStatic(BundleUtils.class)) {
            mocked.when(() -> BundleUtils.getOsgiService(Object.class, null)).thenReturn(fakeService);

            assertThat(BundleUtils.getOsgiService(Object.class, null)).isSameAs(fakeService);
        }
    }

    @Test
    @DisplayName("static mocks are scoped to their try-with-resources block and close() does not throw")
    void staticMockDoesNotLeakOutsideItsScope() {
        // Proves the try-with-resources lifecycle itself (registration + deregistration of the
        // static mock) works cleanly. We deliberately do not call the real SettingsBean.getInstance()
        // after the block closes - that would require a live, bootstrapped Jahia container.
        assertThatCode(() -> {
            try (MockedStatic<SettingsBean> ignored = mockStatic(SettingsBean.class)) {
                when(SettingsBean.getInstance()).thenReturn(Mockito.mock(SettingsBean.class));
            }
        }).doesNotThrowAnyException();
    }
}
