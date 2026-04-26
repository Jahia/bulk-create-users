import {DocumentNode} from 'graphql';

describe('Bulk Create Users — UI Column Selection', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteUser: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');

    const ADMIN_ROUTE = '/jahia/administration/bulkCreateUsers';

    const deleteTestUsers = () => {
        cy.apollo({mutation: deleteUser, variables: {path: '/users/bcu-test-user1'}, failOnStatusCode: false});
        cy.apollo({mutation: deleteUser, variables: {path: '/users/bcu-test-user2'}, failOnStatusCode: false});
    };

    before(() => {
        cy.login();
        deleteTestUsers();
    });

    after(() => {
        deleteTestUsers();
    });

    // ─── Column detection ─────────────────────────────────────────────────────────

    describe('Column detection', () => {
        it('shows column section after selecting a valid CSV', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-columns', {timeout: 5000}).should('be.visible');
        });

        it('shows required column checkboxes (disabled)', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-col-req-j-nodename', {timeout: 5000}).should('be.disabled');
            cy.get('#bcu-col-req-j-password').should('be.disabled');
            cy.get('#bcu-col-req-j-firstName').should('be.disabled');
            cy.get('#bcu-col-req-j-lastName').should('be.disabled');
        });

        it('marks all required columns as checked when present', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-col-req-j-nodename', {timeout: 5000}).should('be.checked');
            cy.get('#bcu-col-req-j-firstName').should('be.checked');
            cy.get('#bcu-col-req-j-lastName').should('be.checked');
        });

        it('shows optional columns for CSVs with extra columns', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users-with-email.csv', {force: true});
            cy.get('#bcu-col-opt-j-email', {timeout: 5000}).should('exist');
        });

        it('pre-checks all optional columns', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users-with-email.csv', {force: true});
            cy.get('#bcu-col-opt-j-email', {timeout: 5000}).should('be.checked');
        });

        it('allows toggling optional columns', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users-with-email.csv', {force: true});
            cy.get('#bcu-col-opt-j-email', {timeout: 5000}).uncheck();
            cy.get('#bcu-col-opt-j-email').should('not.be.checked');
            cy.get('#bcu-col-opt-j-email').check();
            cy.get('#bcu-col-opt-j-email').should('be.checked');
        });

        it('shows missing badge and disables Submit when required columns are absent', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/missing-columns.csv', {force: true});
            cy.get('#bcu-missing-required', {timeout: 5000}).should('be.visible');
            cy.get('#bcu-submit').should('be.disabled');
        });

        it('re-parses headers when delimiter is changed', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-columns', {timeout: 5000}).should('be.visible');
            // Change to semicolon — the CSV uses commas so headers will be wrong
            cy.get('#bcu-delimiter').clear().type(';');
            // The required columns will now show as missing (wrong parse)
            cy.get('#bcu-missing-required').should('be.visible');
            // Restore comma
            cy.get('#bcu-delimiter').clear().type(',');
            cy.get('#bcu-missing-required').should('not.exist');
        });

        it('clears column section after Cancel', () => {
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-columns', {timeout: 5000}).should('be.visible');
            cy.get('#bcu-cancel').click();
            cy.get('#bcu-columns').should('not.exist');
        });
    });
});
