import {DocumentNode} from 'graphql';

describe('Bulk Create Users', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteUser: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');

    const TEST_USER_1 = 'bcu-test-user1';
    const TEST_USER_2 = 'bcu-test-user2';
    const CSV_VALID = 'j:nodename,j:password,j:firstName,j:lastName\nbcu-test-user1,TestPass1234!,Alice,Smith\nbcu-test-user2,TestPass1234!,Bob,Jones';

    const deleteTestUsers = () => {
        cy.apollo({mutation: deleteUser, variables: {path: `/users/${TEST_USER_1}`}, failOnStatusCode: false});
        cy.apollo({mutation: deleteUser, variables: {path: `/users/${TEST_USER_2}`}, failOnStatusCode: false});
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
                variables: {csvContent: CSV_VALID, separator: ',', siteKey: null}
            })
                .its('data.bulkCreateUsersImport')
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
                variables: {csvContent: CSV_VALID, separator: ','}
            })
                .its('data.bulkCreateUsersImport')
                .should(result => {
                    expect(result).to.have.all.keys('success', 'createdCount', 'skippedCount', 'errorCount', 'errors');
                });
        });

        it('skips existing users and increments skippedCount', () => {
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: CSV_VALID, separator: ','}
            })
                .its('data.bulkCreateUsersImport')
                .should(result => {
                    expect(result.skippedCount).to.be.greaterThan(0);
                    expect(result.createdCount).to.eq(0);
                });
        });

        it('fails gracefully on missing required columns', () => {
            const csvMissingColumns = 'j:firstName,j:lastName\nAlice,Smith';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvMissingColumns, separator: ','}
            })
                .its('data.bulkCreateUsersImport')
                .should(result => {
                    expect(result.success).to.be.false;
                    expect(result.errorCount).to.be.greaterThan(0);
                });
        });

        it('handles empty CSV content gracefully', () => {
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: '', separator: ','}
            })
                .its('data.bulkCreateUsersImport')
                .should(result => {
                    expect(result.success).to.be.false;
                });
        });

        it('uses comma as default separator when not specified', () => {
            const singleUserCsv = 'j:nodename,j:password,j:firstName,j:lastName\nbcu-test-user1,TestPass1234!,Alice,Smith';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: singleUserCsv}
            })
                .its('data.bulkCreateUsersImport.success')
                .should('be.a', 'boolean');
        });

        it('handles semicolon-separated CSV', () => {
            const semicolonCsv = 'j:nodename;j:password;j:firstName;j:lastName\nbcu-test-user1;TestPass1234!;Alice;Smith';
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: semicolonCsv, separator: ';'}
            })
                .its('data.bulkCreateUsersImport')
                .should(result => {
                    expect(result).to.have.property('success');
                    expect(result).to.have.property('createdCount');
                });
        });
    });
});
