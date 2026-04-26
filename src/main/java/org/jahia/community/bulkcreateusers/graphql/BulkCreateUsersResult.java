package org.jahia.community.bulkcreateusers.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

import java.util.List;

@GraphQLName("BulkCreateUsersResult")
@GraphQLDescription("Result of a bulk user creation operation")
public class BulkCreateUsersResult {

    private final boolean success;
    private final int createdCount;
    private final int skippedCount;
    private final int errorCount;
    private final List<String> errors;

    public BulkCreateUsersResult(boolean success, int createdCount, int skippedCount, int errorCount, List<String> errors) {
        this.success = success;
        this.createdCount = createdCount;
        this.skippedCount = skippedCount;
        this.errorCount = errorCount;
        this.errors = errors;
    }

    @GraphQLField
    @GraphQLName("success")
    @GraphQLDescription("True if no errors occurred during the import")
    public boolean isSuccess() {
        return success;
    }

    @GraphQLField
    @GraphQLName("createdCount")
    @GraphQLDescription("Number of users successfully created")
    public int getCreatedCount() {
        return createdCount;
    }

    @GraphQLField
    @GraphQLName("skippedCount")
    @GraphQLDescription("Number of rows skipped because the user already existed")
    public int getSkippedCount() {
        return skippedCount;
    }

    @GraphQLField
    @GraphQLName("errorCount")
    @GraphQLDescription("Number of rows that failed due to errors")
    public int getErrorCount() {
        return errorCount;
    }

    @GraphQLField
    @GraphQLName("errors")
    @GraphQLDescription("Per-row error messages for failed rows")
    public List<String> getErrors() {
        return errors;
    }
}
