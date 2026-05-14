import {DocumentNode} from 'graphql';

describe('Bulk Create Users — UI Import flow', () => {
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

    // ─── File selection ───────────────────────────────────────────────────────────

    describe('File selection', () => {
        it('enables Submit after a CSV file is selected', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').should('not.be.disabled');
        });

        it('displays selected file name and size', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.contains('valid-users.csv').should('be.visible');
        });

        it('resets the form when Cancel is clicked', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-cancel').click();
            cy.get('#bcu-submit').should('be.disabled');
            cy.contains('valid-users.csv').should('not.exist');
        });
    });

    // ─── Import flow ──────────────────────────────────────────────────────────────

    describe('Import flow', () => {
        it('shows success message after importing valid CSV', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('[id="bcu-message-success"]', {timeout: 15000}).should('be.visible');
        });

        it('shows result box with createdCount after successful import', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-result-created').should('exist');
        });

        it('shows skippedCount when re-importing the same users', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-result-skipped').invoke('text').then(Number).should('be.greaterThan', 0);
        });

        it('shows error message for CSV missing required columns', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/missing-columns.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('[id="bcu-message-error"]', {timeout: 15000}).should('be.visible');
        });

        it('clears result box after Cancel', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-cancel').click();
            cy.get('#bcu-result').should('be.empty')
              .and('match', ':empty');
        });
    });
});
