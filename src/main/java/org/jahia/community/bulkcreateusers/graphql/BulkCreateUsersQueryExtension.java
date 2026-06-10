package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.settings.SettingsBean;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLName("BulkCreateUsersQueries")
@GraphQLDescription("Bulk create users queries")
public class BulkCreateUsersQueryExtension {

    private BulkCreateUsersQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("bulkCreateUsersMaxUploadSize")
    @GraphQLDescription("Maximum allowed CSV upload size in bytes, as configured in Jahia settings")
    @GraphQLRequiresPermission("adminUsersBulkCreate")
    public static Long maxUploadSize() {
        return SettingsBean.getInstance().getJahiaFileUploadMaxSize();
    }
}
