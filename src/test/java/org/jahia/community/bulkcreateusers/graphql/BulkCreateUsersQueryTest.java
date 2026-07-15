package org.jahia.community.bulkcreateusers.graphql;

import org.jahia.settings.SettingsBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Bug 2 (SUPPORT-646 Stage 7): {@link BulkCreateUsersQuery#maxUploadSize()} must advertise the
 * same value {@link BulkCreateUsersMutation#importUsers} actually enforces - not the raw
 * {@code jahiaFileUploadMaxSize} setting - since the UI's client-side pre-check
 * ({@code createUsers.jsx}) trusts this value to reject an oversized file before ever calling the
 * mutation. Previously the query returned the raw (often much larger) configured setting, making
 * the advertised limit unreachable dead weight for any payload between the real GraphQL
 * transport ceiling (~20M characters, see {@link BulkCreateUsersMutation}) and that setting.
 */
class BulkCreateUsersQueryTest {

    @Test
    @DisplayName("delegates to BulkCreateUsersMutation.effectiveMaxUploadSize(), clamping a large configured setting")
    void delegatesToEffectiveMaxUploadSize() {
        try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class)) {
            final SettingsBean settingsBean = mock(SettingsBean.class);
            when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(104_857_600L);
            settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);

            final Long result = new BulkCreateUsersQuery().maxUploadSize();

            assertThat(result).isEqualTo(BulkCreateUsersMutation.GRAPHQL_JSON_VARIABLE_MAX_LENGTH);
        }
    }

    @Test
    @DisplayName("returns a small configured setting unchanged when it is below the transport ceiling")
    void returnsSmallConfiguredSettingUnchanged() {
        try (MockedStatic<SettingsBean> settingsMock = mockStatic(SettingsBean.class)) {
            final SettingsBean settingsBean = mock(SettingsBean.class);
            when(settingsBean.getJahiaFileUploadMaxSize()).thenReturn(1_000_000L);
            settingsMock.when(SettingsBean::getInstance).thenReturn(settingsBean);

            final Long result = new BulkCreateUsersQuery().maxUploadSize();

            assertThat(result).isEqualTo(1_000_000L);
        }
    }
}
