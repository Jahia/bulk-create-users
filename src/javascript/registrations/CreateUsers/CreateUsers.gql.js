import {gql} from '@apollo/client';

export const BULK_CREATE_USERS_IMPORT = gql`
    mutation BulkCreateUsersImport($csvContent: String!, $separator: String, $siteKey: String) {
        bulkCreateUsersImport(csvContent: $csvContent, separator: $separator, siteKey: $siteKey) {
            success
            createdCount
            skippedCount
            errorCount
            errors
        }
    }
`;
