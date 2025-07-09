# Bulk Create Users

This community module provides a tool to bulk create users in Jahia, from a CSV file. It also supports assigning users to groups.

## Features

- Bulk user creation from CSV file
- Group assignment for users

## Usage

- Deploy the module on your Jahia environment
- Bulk create option will be available under settings

## CSV Format

The CSV file should include the following columns:

- `j:nodename` (username)
- `j:password`
- `j:firstName`
- `j:lastName`
- `groups` (optional, `$`-separated group names)

**Example:**
```
j:nodename,j:password,firstName,lastName,groups
user1,pass1,John,Doe,group1$group2
user2,pass2,Jane,Smith,group1
```

## License
MIT
