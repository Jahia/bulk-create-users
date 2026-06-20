package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLDescription("Bulk Create Users queries")
public class BulkCreateUsersQueryExtension {

    private BulkCreateUsersQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("bulkCreateUsers")
    @GraphQLDescription("Bulk Create Users query namespace")
    public static BulkCreateUsersQuery bulkCreateUsers() {
        return new BulkCreateUsersQuery();
    }
}
