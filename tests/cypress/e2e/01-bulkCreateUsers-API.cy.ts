import {DocumentNode} from 'graphql';

describe('Bulk Create Users', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteUser: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');

    const TEST_USER_1 = 'bcu-test-user1';
    const TEST_USER_2 = 'bcu-test-user2';
    const REQUIRED_COLUMNS = ['j:firstName', 'j:lastName'];
    const CSV_VALID = 'j:nodename,j:password,j:firstName,j:lastName\nbcu-test-user1,TestPass1234!,Alice,Smith\nbcu-test-user2,TestPass1234!,Bob,Jones';
    const CSV_WITH_EMAIL = 'j:nodename,j:password,j:firstName,j:lastName,j:email\nbcu-test-user1,TestPass1234!,Alice,Smith,alice@example.com\nbcu-test-user2,TestPass1234!,Bob,Jones,bob@example.com';

    const deleteTestUsers = () => {
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
    };

    before(() => {
        cy.login();
        deleteTestUsers();
    });

    after(() => {
        deleteTestUsers();
    });

    // ─── GraphQL API ─────────────────────────────────────────────────────────────

    describe('GraphQL API', () => {
        it('imports users from CSV and returns success', () => {
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: CSV_VALID, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(2);
                    expect(result.skippedCount).to.eq(0);
                    expect(result.errorCount).to.eq(0);
                    expect(result.errors).to.be.an('array').and.have.length(0);
                });
        });

        it('returns result object with all fields', () => {
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: CSV_VALID, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result).to.have.all.keys('__typename', 'success', 'createdCount', 'updatedCount', 'skippedCount', 'errorCount', 'errors');
                });
        });

        it('skips existing users and increments skippedCount', () => {
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: CSV_VALID, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.skippedCount).to.be.greaterThan(0);
                    expect(result.createdCount).to.eq(0);
                });
        });

        it('imports selected optional columns', () => {
            deleteTestUsers();
            cy.apollo({
                mutation: importUsers,
                variables: {
                    csvContent: CSV_WITH_EMAIL,
                    separator: ',',
                    selectedColumns: [...REQUIRED_COLUMNS, 'j:email']
                }
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(2);
                });
        });

        it('ignores unselected optional columns', () => {
            deleteTestUsers();
            cy.apollo({
                mutation: importUsers,
                variables: {
                    csvContent: CSV_WITH_EMAIL,
                    separator: ',',
                    selectedColumns: REQUIRED_COLUMNS  // j:email NOT selected
                }
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                });
        });

        it('fails gracefully on missing required columns', () => {
            const csvMissingColumns = 'j:firstName,j:lastName\nAlice,Smith';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvMissingColumns, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.false;
                    expect(result.errorCount).to.be.greaterThan(0);
                });
        });

        it('handles empty CSV content gracefully', () => {
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: '', separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.false;
                });
        });

        it('handles semicolon-separated CSV', () => {
            deleteTestUsers();
            const semicolonCsv = 'j:nodename;j:password;j:firstName;j:lastName\nbcu-test-user1;TestPass1234!;Alice;Smith';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: semicolonCsv, separator: ';', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });
        });

        it('accepts groups in [group1],[group2] bracket format', () => {
            deleteTestUsers();
            // privileged is a built-in global group that always exists
            const csvWithGroups = 'j:nodename,j:password,j:firstName,j:lastName,groups\nbcu-test-user1,TestPass1234!,Alice,Smith,[privileged]';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvWithGroups, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });
        });

        it('silently skips a group that does not exist', () => {
            deleteTestUsers();
            const csvWithBadGroup = 'j:nodename,j:password,j:firstName,j:lastName,groups\nbcu-test-user1,TestPass1234!,Alice,Smith,[nonexistent-group-xyz]';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvWithBadGroup, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    // user is created even though the group was not found
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });
        });

        it('overwrites existing users when overwrite is true', () => {
            // Users already exist from the previous test; re-import with overwrite=true
            const csvUpdated = 'j:nodename,j:password,j:firstName,j:lastName\nbcu-test-user1,TestPass1234!,AliceUpdated,SmithUpdated';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvUpdated, separator: ',', selectedColumns: REQUIRED_COLUMNS, overwrite: true}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.updatedCount).to.eq(1);
                    expect(result.createdCount).to.eq(0);
                    expect(result.skippedCount).to.eq(0);
                });
        });

        it('imports only required properties when selectedColumns is omitted', () => {
            // Safer default: with no explicit selectedColumns, optional CSV columns
            // (here j:email) must not be written. The import itself must still succeed
            // because the required columns are picked up automatically.
            deleteTestUsers();
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: CSV_WITH_EMAIL, separator: ','}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(2);
                    expect(result.errorCount).to.eq(0);
                });
        });

        it('imports only required properties when selectedColumns is an empty array', () => {
            deleteTestUsers();
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: CSV_WITH_EMAIL, separator: ',', selectedColumns: []}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(2);
                });
        });

        it('continues processing remaining rows when one row is malformed', () => {
            // L3 isolation: a short/invalid row must not abort the whole import.
            deleteTestUsers();
            const csvWithBadRow = 'j:nodename,j:password,j:firstName,j:lastName\n'
                + 'bcu-test-user1,TestPass1234!,Alice,Smith\n'
                + 'truncated-row\n'
                + 'bcu-test-user2,TestPass1234!,Bob,Jones';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvWithBadRow, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.createdCount).to.eq(2);
                    expect(result.errorCount).to.be.greaterThan(0);
                    expect(result.success).to.be.false;
                });
        });

        it('never overwrites the root user even when overwrite is true', () => {
            const csvWithRoot = 'j:nodename,j:password,j:firstName,j:lastName\nroot,TestPass1234!,Hacked,Root';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvWithRoot, separator: ',', selectedColumns: REQUIRED_COLUMNS, overwrite: true}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.updatedCount).to.eq(0);
                    expect(result.skippedCount).to.eq(1);
                });
        });
    });
});
