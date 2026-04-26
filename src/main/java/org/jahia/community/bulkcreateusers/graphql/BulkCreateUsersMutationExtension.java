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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLName("BulkCreateUsersMutations")
@GraphQLDescription("Bulk create users mutations")
public class BulkCreateUsersMutationExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(BulkCreateUsersMutationExtension.class);

    private BulkCreateUsersMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("bulkCreateUsersImport")
    @GraphQLDescription("Imports users from a CSV string; returns a detailed result with per-user counts and errors")
    @GraphQLRequiresPermission("adminUsers")
    public static BulkCreateUsersResult importUsers(
            @GraphQLName("csvContent") @GraphQLNonNull final String csvContent,
            @GraphQLName("separator") final String separator,
            @GraphQLName("siteKey") final String siteKey,
            @GraphQLName("selectedColumns") final List<String> selectedColumns) {
        final UsersHandler handler = BundleUtils.getOsgiService(UsersHandler.class, null);
        if (handler == null) {
            LOGGER.error("UsersHandler service is not available");
            return new BulkCreateUsersResult(false, 0, 0, 1, Collections.singletonList("Service unavailable"));
        }
        final String sep = (separator != null && !separator.isEmpty()) ? separator : ",";
        final String site = (siteKey != null && !siteKey.isEmpty()) ? siteKey : null;
        try {
            return handler.importUsers(csvContent, sep, site, selectedColumns);
        } catch (Exception e) {
            LOGGER.error("Error during bulk user import", e);
            return new BulkCreateUsersResult(false, 0, 0, 1, Collections.singletonList(e.getMessage()));
        }
    }
}
