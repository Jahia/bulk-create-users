package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.community.bulkcreateusers.users.UsersHandler;
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

@GraphQLName("BulkCreateUsersMutation")
@GraphQLDescription("Bulk Create Users mutations")
public class BulkCreateUsersMutation {

    private static final Logger LOGGER = LoggerFactory.getLogger(BulkCreateUsersMutation.class);

    // Permission required to bulk-import users at the server (global) scope. This is the same
    // fine-grained "adminUsersBulkCreate" permission previously (incorrectly) also gated by
    // @GraphQLRequiresPermission on importUsers() - see the SUPPORT-646 correction comment in
    // importUsers() for why that annotation was removed. A role granting only adminUsersBulkCreate
    // can still perform the global import end-to-end (no broader "adminUsers" needed).
    private static final String SERVER_USERS_PERMISSION = "adminUsersBulkCreate";
    // Permission required to manage users within a single site (site-scope branch is unchanged).
    private static final String SITE_USERS_PERMISSION = "siteAdminUsers";
    // Broad server-level users-admin permission, still accepted as a fallback for the per-site scope.
    private static final String GLOBAL_USERS_ADMIN_PERMISSION = "adminUsers";
    // A site key is a short, opaque identifier: letters, digits, dash and underscore only. Enforcing this
    // shape also prevents path traversal when the key is composed into the "/sites/<key>" node path below.
    private static final Pattern SITE_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,150}");

    // Bug 2 (SUPPORT-646 Stage 7): the real, reachable ceiling for this GraphQL submission
    // mechanism (csvContent sent as a plain JSON string variable) is NOT jahiaFileUploadMaxSize -
    // it is Jackson's own DoS-hardening default, StreamReadConstraints.getMaxStringLength()
    // (20,000,000 characters, the default since Jackson 2.15), enforced deep inside
    // graphql-java-kickstart's GraphQLObjectMapper/VariablesDeserializer (part of the
    // graphql-dxm-provider bundle, a Jahia-core dependency this module does not own/build).
    // Confirmed empirically against a live Jahia 8.2 container: a request with a 19,923,013-byte
    // "variables.csvContent" value reaches this resolver (HTTP 200); one with 20,000,070 bytes
    // never does - it is rejected while Jackson deserializes the request body, before GraphQL
    // query parsing or this resolver even runs:
    //   graphql.kickstart.servlet.InvocationInputParseException: Request parsing failed
    //   Caused by: com.fasterxml.jackson.databind.JsonMappingException: String value length
    //     (20000011) exceeds the maximum allowed (20000000, from
    //     `StreamReadConstraints.getMaxStringLength()`) (through reference chain:
    //     graphql.kickstart.execution.GraphQLRequest["variables"])
    // This means jahiaFileUploadMaxSize's own default (100 MiB) is unreachable dead code for any
    // payload above ~20 MB sent through this mechanism: the module's own graceful size check
    // below never gets a chance to run. Clamping the effective/advertised limit to this ceiling
    // (see effectiveMaxUploadSize()) makes the check reachable again for realistic payloads, and
    // - since the UI's own client-side pre-check in createUsers.jsx already compares the file
    // size against this same advertised bulkCreateUsers.maxUploadSize value before ever calling
    // the mutation - fixes the user-facing symptom without any UI code change: a user picking an
    // oversized file now gets the module's friendly "file too large" message immediately, instead
    // of a raw network failure after the whole file has already been read and submitted.
    // This ceiling is not something this module's own code can raise (it is another OSGi
    // bundle's internal Jackson configuration, snapshotted into a JsonFactory this module never
    // constructs), so the fix here is to stop advertising/enforcing a limit this transport can
    // never actually deliver on, rather than attempting to patch graphql-dxm-provider itself.
    static final long GRAPHQL_JSON_VARIABLE_MAX_LENGTH = 20_000_000L;

    @GraphQLField
    @GraphQLName("importUsers")
    @GraphQLDescription("Imports users from a CSV string; returns a detailed result with per-user counts and errors")
    public BulkCreateUsersResult importUsers(
            @GraphQLName("csvContent") @GraphQLNonNull final String csvContent,
            @GraphQLName("separator") final String separator,
            @GraphQLName("siteKey") final String siteKey,
            @GraphQLName("selectedColumns") final List<String> selectedColumns,
            @GraphQLName("overwrite") final Boolean overwrite) {
        final long maxBytes = effectiveMaxUploadSize();
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
        // SUPPORT-646 correction: this mutation deliberately does NOT use @GraphQLRequiresPermission.
        // graphql-dxm-provider's GqlJcrPermissionChecker.checkPermissions() always resolves that
        // annotation's permission against the JCR root ("/") unless the permission string itself embeds
        // a path via a "perm/path" convention - it has no way to know this mutation's own siteKey
        // argument. Using the annotation here would permanently deny a legitimate site-scoped caller
        // (granted only siteAdminUsers on their own /sites/<siteKey>, not adminUsersBulkCreate on the
        // repository root) before this method body - including the check below - ever runs. The
        // programmatic check below is the ONLY authorization gate for this mutation, and is scope-aware
        // by construction: it resolves the permission against the correct node for the requested scope
        // (repository root for a global import, /sites/<siteKey> for a site-scoped one).
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
     * The real upload-size ceiling for this GraphQL submission mechanism: the smaller of the
     * operator-configured {@code jahiaFileUploadMaxSize} and the hard, third-party
     * {@link #GRAPHQL_JSON_VARIABLE_MAX_LENGTH} transport ceiling (see the field comment for the
     * full root-cause explanation). A non-positive {@code jahiaFileUploadMaxSize} means "no
     * configured limit" (matching the pre-existing {@code maxBytes > 0} check), in which case
     * only the transport ceiling applies. Shared by both the mutation's own size check and
     * {@link BulkCreateUsersQuery#maxUploadSize()} so the advertised limit and the enforced limit
     * can never drift apart. Visible for testing.
     */
    static long effectiveMaxUploadSize() {
        final long configuredMax = SettingsBean.getInstance().getJahiaFileUploadMaxSize();
        return configuredMax > 0 ? Math.min(configuredMax, GRAPHQL_JSON_VARIABLE_MAX_LENGTH) : GRAPHQL_JSON_VARIABLE_MAX_LENGTH;
    }

    /**
     * Verifies the authenticated caller actually holds the users-admin permission on the requested scope,
     * evaluated through their own (ACL-respecting) session — not the system session used for the writes.
     *
     * <ul>
     *   <li>{@code siteKey == null} (global users): requires {@code adminUsersBulkCreate} on the repository
     *       root, matching the fine-grained gate so the bulk-create role works end-to-end.</li>
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
            return siteNode.hasPermission(SITE_USERS_PERMISSION) || siteNode.hasPermission(GLOBAL_USERS_ADMIN_PERMISSION);
        } catch (PathNotFoundException e) {
            LOGGER.warn("Authorization denied: requested site does not exist", e);
            return false;
        } catch (RepositoryException e) {
            LOGGER.error("Authorization check failed; denying import", e);
            return false;
        }
    }
}
