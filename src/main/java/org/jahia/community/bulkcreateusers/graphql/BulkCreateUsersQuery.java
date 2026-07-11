package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;

@GraphQLName("BulkCreateUsersQuery")
@GraphQLDescription("Bulk Create Users queries")
public class BulkCreateUsersQuery {

    @GraphQLField
    @GraphQLName("maxUploadSize")
    @GraphQLDescription("Maximum allowed CSV upload size in bytes, as actually enforced by the import mutation "
            + "(the smaller of the configured Jahia setting and this GraphQL transport's own hard ceiling)")
    @GraphQLRequiresPermission("adminUsersBulkCreate")
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
