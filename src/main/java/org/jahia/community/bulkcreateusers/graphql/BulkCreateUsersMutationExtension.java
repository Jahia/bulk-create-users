package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.bulkcreateusers.users.UsersHandler;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.settings.SettingsBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLName("BulkCreateUsersMutations")
@GraphQLDescription("Bulk create users mutations")
public class BulkCreateUsersMutationExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(BulkCreateUsersMutationExtension.class);

    // Permission required to manage users at the server (global) scope.
    private static final String SERVER_USERS_PERMISSION = "adminUsers";
    // Permission required to manage users within a single site.
    private static final String SITE_USERS_PERMISSION = "siteAdminUsers";
    // A site key is a short, opaque identifier: letters, digits, dash and underscore only. Enforcing this
    // shape also prevents path traversal when the key is composed into the "/sites/<key>" node path below.
    private static final Pattern SITE_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,150}");

    private BulkCreateUsersMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("bulkCreateUsersImport")
    @GraphQLDescription("Imports users from a CSV string; returns a detailed result with per-user counts and errors")
    @GraphQLRequiresPermission("adminUsersBulkCreate")
    public static BulkCreateUsersResult importUsers(
            @GraphQLName("csvContent") @GraphQLNonNull final String csvContent,
            @GraphQLName("separator") final String separator,
            @GraphQLName("siteKey") final String siteKey,
            @GraphQLName("selectedColumns") final List<String> selectedColumns,
            @GraphQLName("overwrite") final Boolean overwrite) {
        final long maxBytes = SettingsBean.getInstance().getJahiaFileUploadMaxSize();
        if (maxBytes > 0 && csvContent.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            LOGGER.warn("Rejecting bulk user import: payload exceeds configured upload size limit of {} bytes", maxBytes);
            return new BulkCreateUsersResult(false, 0, 0, 0, 1,
                    Collections.singletonList("CSV payload exceeds the configured upload size limit"));
        }
        final UsersHandler handler = BundleUtils.getOsgiService(UsersHandler.class, null);
        if (handler == null) {
            LOGGER.error("UsersHandler service is not available");
            return new BulkCreateUsersResult(false, 0, 0, 0, 1, Collections.singletonList("Service unavailable"));
        }
        final String sep = (separator != null && !separator.isEmpty()) ? separator : ",";
        final String site = (siteKey != null && !siteKey.isEmpty()) ? siteKey : null;
        if (site != null && !isValidSiteKey(site)) {
            LOGGER.warn("Rejecting bulk user import: malformed siteKey");
            return new BulkCreateUsersResult(false, 0, 0, 0, 1,
                    Collections.singletonList("Invalid siteKey"));
        }
        // The @GraphQLRequiresPermission gate above is not scope-aware: it does not verify that the caller
        // may administer the *specific* target. Re-check the permission against the resolved scope so a
        // caller cannot pivot to a site (or to the global user base) they do not administer.
        if (!isAuthorizedForScope(site)) {
            LOGGER.warn("Rejecting bulk user import: caller not authorized for the requested scope");
            return new BulkCreateUsersResult(false, 0, 0, 0, 1,
                    Collections.singletonList("Not authorized to manage users in the requested scope"));
        }
        try {
            return handler.importUsers(csvContent, sep, site, selectedColumns, Boolean.TRUE.equals(overwrite));
        } catch (Exception e) {
            LOGGER.error("Error during bulk user import", e);
            return new BulkCreateUsersResult(false, 0, 0, 0, 1,
                    Collections.singletonList("Internal error during bulk user import"));
        }
    }

    /** True when {@code siteKey} is a well-formed, traversal-safe site identifier. Visible for testing. */
    static boolean isValidSiteKey(String siteKey) {
        return siteKey != null && SITE_KEY_PATTERN.matcher(siteKey).matches();
    }

    /**
     * Verifies the authenticated caller actually holds the users-admin permission on the requested scope,
     * evaluated through their own (ACL-respecting) session — not the system session used for the writes.
     *
     * <ul>
     *   <li>{@code siteKey == null} (global users): requires {@code adminUsers} on the repository root.</li>
     *   <li>otherwise: requires {@code siteAdminUsers} (or {@code adminUsers}) on {@code /sites/<siteKey>}.</li>
     * </ul>
     *
     * Fails closed on any repository error or unknown site.
     */
    private static boolean isAuthorizedForScope(String siteKey) {
        try {
            final JCRSessionWrapper session = JCRSessionFactory.getInstance().getCurrentUserSession();
            if (siteKey == null) {
                return session.getNode("/").hasPermission(SERVER_USERS_PERMISSION);
            }
            final JCRNodeWrapper siteNode = session.getNode("/sites/" + siteKey);
            return siteNode.hasPermission(SITE_USERS_PERMISSION) || siteNode.hasPermission(SERVER_USERS_PERMISSION);
        } catch (PathNotFoundException e) {
            LOGGER.warn("Authorization denied: requested site does not exist");
            return false;
        } catch (RepositoryException e) {
            LOGGER.error("Authorization check failed; denying import", e);
            return false;
        }
    }
}
