# Bulk Create Users

A Jahia community module that provides a UI and GraphQL API to bulk-create users from a CSV file, with optional group assignment.

## Features

- Bulk user creation from a CSV file via a React admin UI
- Column selection: choose which optional properties to import per run
- Group assignment using `[group1],[group2]` bracket notation
- Skips existing users (increments skipped count) instead of failing
- Detailed import result: created, skipped, error counts and per-row error messages
- Max upload size enforced from Jahia's own `SettingsBean` configuration
- GraphQL mutation API (`bulkCreateUsersImport`) for programmatic imports
- Site-scoped or global user creation

## Requirements

- Jahia `8.2.1.0` or later
- Module dependencies: `default`, `graphql-dxm-provider`

## Installation

Deploy the module on your Jahia environment. The **Bulk Create Users** option becomes available under **Administration → [site] → Settings → Bulk Create Users**.

## CSV Format

### Required columns

| Column | Description |
|---|---|
| `j:nodename` | Username (must be unique) |
| `j:password` | Initial password |
| `j:firstName` | First name |
| `j:lastName` | Last name |

### Optional columns

| Column | Description |
|---|---|
| `j:email` | Email address |
| `groups` | Bracket-separated group names: `[group1],[group2]` |
| Any `j:*` property | Any other user property supported by Jahia |

The UI displays all optional columns detected in the CSV header and lets you toggle each one before importing. Unselected columns are ignored by the backend.

### Delimiter

The default delimiter is `,`. Any single character is accepted (e.g. `;`, `|`). Set it in the UI before uploading.

### Example

```csv
j:nodename,j:password,j:firstName,j:lastName,j:email,groups
alice,Secret1234!,Alice,Smith,alice@example.com,[editors],[privileged]
bob,Secret1234!,Bob,Jones,bob@example.com,[editors]
```

## GraphQL API

### Query

```graphql
query {
  bulkCreateUsersMaxUploadSize   # returns the configured max size in bytes
}
```

### Mutation

```graphql
mutation {
  bulkCreateUsersImport(
    csvContent: "...",
    separator: ",",
    siteKey: "mySite",          # omit for global users
    selectedColumns: ["j:firstName", "j:lastName", "j:email", "groups"]
  ) {
    success
    createdCount
    skippedCount
    errorCount
    errors
  }
}
```

Both operations require the `adminUsers` permission.
