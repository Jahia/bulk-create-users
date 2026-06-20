package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.settings.SettingsBean;

@GraphQLName("BulkCreateUsersQuery")
@GraphQLDescription("Bulk Create Users queries")
public class BulkCreateUsersQuery {

    @GraphQLField
    @GraphQLName("maxUploadSize")
    @GraphQLDescription("Maximum allowed CSV upload size in bytes, as configured in Jahia settings")
    @GraphQLRequiresPermission("adminUsersBulkCreate")
    public Long maxUploadSize() {
        return SettingsBean.getInstance().getJahiaFileUploadMaxSize();
    }
}
