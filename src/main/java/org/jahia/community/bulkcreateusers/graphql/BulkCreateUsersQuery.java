package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

@GraphQLName("BulkCreateUsersQuery")
@GraphQLDescription("Bulk Create Users queries")
public class BulkCreateUsersQuery {

    // SUPPORT-646 correction: this read-only query deliberately does NOT use @GraphQLRequiresPermission.
    // graphql-dxm-provider's GqlJcrPermissionChecker.checkPermissions() always resolves that annotation's
    // permission against the JCR root ("/") unless the permission string embeds a "perm/path" convention -
    // it cannot be made scope-aware. With @GraphQLRequiresPermission("adminUsersBulkCreate") a legitimate
    // site-scoped-only admin (granted siteAdminUsers on their own /sites/<siteKey>, not adminUsersBulkCreate
    // at the repository root) was permanently denied this query, yet the UI's client-side pre-check
    // (createUsers.jsx) relies on it to reject an oversized file before ever calling the mutation - so the
    // annotation silently broke the upload UX for exactly the callers the scope-aware mutation now supports.
    // Unlike importUsers(), this query takes NO scope argument and returns only a non-sensitive GLOBAL config
    // ceiling (an upload-size limit, never user data), so there is nothing to scope-check against and nothing
    // to protect; the mutation's own scope-aware isAuthorizedForScope() gate remains the sole authorization
    // boundary for the privileged (write) operation. Transport-level API access is still governed by Jahia's
    // security-filter bundle.
    @GraphQLField
    @GraphQLName("maxUploadSize")
    @GraphQLDescription("Maximum allowed CSV upload size in bytes, as actually enforced by the import mutation "
            + "(the smaller of the configured Jahia setting and this GraphQL transport's own hard ceiling)")
    public Long maxUploadSize() {
        // Bug 2 (SUPPORT-646 Stage 7): must return the same value the mutation actually enforces
        // (BulkCreateUsersMutation#effectiveMaxUploadSize()), not the raw jahiaFileUploadMaxSize
        // setting alone - otherwise this advertised limit is a lie for payloads between the
        // transport's real ~20 MB ceiling and the (often much larger) configured setting. The
        // UI's own client-side pre-check (createUsers.jsx) trusts this value to reject an
        // oversized file before ever calling the mutation, so it must be accurate.
        return BulkCreateUsersMutation.effectiveMaxUploadSize();
    }
}
