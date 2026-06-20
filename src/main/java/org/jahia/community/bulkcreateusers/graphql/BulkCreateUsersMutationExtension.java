package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLDescription("Bulk Create Users mutations")
public class BulkCreateUsersMutationExtension {

    private BulkCreateUsersMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("bulkCreateUsers")
    @GraphQLDescription("Bulk Create Users mutation namespace")
    public static BulkCreateUsersMutation bulkCreateUsers() {
        return new BulkCreateUsersMutation();
    }
}
