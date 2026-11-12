import {DocumentNode} from 'graphql';
import {context} from '@jahia/cypress';

describe('Bulk Create Users — UI Import flow', () => {
    const ADMIN_ROUTE = '/jahia/administration/bulkCreateUsers';

    // See SUPPORT-646 Stage 6: the previous deleteUser.graphql cleanup used a raw JCR
    // mutateNodesByQuery delete, which Jahia rejects for jnt:user nodes
    // (AccessDeniedException), silently swallowed by failOnStatusCode: false (the
    // `variables: {path: ...}` it was called with here were also ignored - the query never
    // referenced that variable). Use the proper JahiaUserManagerService-backed cleanup
    // script instead.
    const deleteTestUsers = () => {
        cy.executeGroovy('groovy/deleteAllTestUsers.groovy');
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
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').should('not.be.disabled');
        });

        it('displays selected file name and size', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.contains('valid-users.csv').should('be.visible');
        });

        it('resets the form when Cancel is clicked', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
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
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('[id="bcu-message-success"]', {timeout: 15000}).should('be.visible');
        });

        it('shows result box with createdCount after successful import', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-result-created').should('exist');
        });

        it('shows skippedCount when re-importing the same users', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-result-skipped').invoke('text').then(Number).should('be.greaterThan', 0);
        });

        // FT-029 (migrated from Selenium ManageUsersTest#importCSVUsers): the legacy test imported
        // a CSV of 5 users (csv1/csv2/csv19/csv34/csv52) via the old admin screen and asserted a
        // literal "Successfully created user <name>" line per row. That screen and message no
        // longer exist — this module's rewritten UI reports an aggregate "N user(s) created
        // successfully" instead (see en.json `result.success`). The intent (importing this CSV
        // creates exactly these 5 users, with their CSV-supplied properties persisted, and each
        // can then log in) is re-expressed against the actual mechanism rather than the old wording.
        it('creates all 5 users from a CSV with correct properties and each can log in (FT-029)', () => {
            context.tag('user-management', 'import', 'csv', 'admin');
            const userProperty: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/userProperty.graphql');
            const rows = [
                {username: 'csv1', firstName: 'Alice', lastName: 'Anderson'},
                {username: 'csv2', firstName: 'Bob', lastName: 'Brown'},
                {username: 'csv19', firstName: 'Carol', lastName: 'Clark'},
                {username: 'csv34', firstName: 'David', lastName: 'Diaz'},
                {username: 'csv52', firstName: 'Eve', lastName: 'Evans'}
            ];

            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users-5.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-result-created').invoke('text').then(Number).should('eq', rows.length);
            cy.get('#bcu-message-success').should('be.visible').and('contain', `${rows.length}`);

            rows.forEach(row => {
                cy.apollo({query: userProperty, variables: {username: row.username, propertyName: 'j:firstName'}})
                    .its('data.admin.userAdmin.user.value')
                    .should('eq', row.firstName);
                cy.apollo({query: userProperty, variables: {username: row.username, propertyName: 'j:lastName'}})
                    .its('data.admin.userAdmin.user.value')
                    .should('eq', row.lastName);
            });

            // Prove at least one imported user can actually authenticate — cy.login() asserts the
            // 302 redirect a successful login produces (see @jahia/cypress support/login.ts), so a
            // wrong/unpersisted password would fail this assertion rather than silently pass.
            cy.login('csv1', 'TestPass1234!');
        });

        // FT-030 (migrated from Selenium ManageUsersTest#importCSVUsers, second half): the legacy
        // test submitted a CSV missing j:nodename/j:password and asserted a literal server-side
        // error message after submit. The rewritten UI validates client-side instead — missing
        // required columns are surfaced immediately via #bcu-missing-required (with the exact
        // "Required columns not found in CSV: ..." text from en.json `columns.missingRequired`)
        // and the Submit button is disabled, so the bad CSV can never reach the server at all. This
        // test previously clicked #bcu-submit anyway and expected a post-submit error box
        // (#bcu-message-error) — a stale assumption that no longer matches the real (and more
        // correct) rejection mechanism; it failed with "cy.click() ... disabled" against a live
        // instance even before this migration, unrelated to any dependency change here. Re-expressed
        // against the actual, earlier rejection point.
        it('rejects a CSV missing required columns before submission is possible (FT-030)', () => {
            context.tag('user-management', 'import', 'csv', 'validation', 'admin');
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/missing-columns.csv', {force: true});
            cy.get('#bcu-missing-required', {timeout: 15000})
                .should('be.visible')
                .and('contain', 'Required columns not found in CSV: j:nodename, j:password');
            cy.get('#bcu-submit').should('be.disabled');
        });

        it('clears result box after Cancel', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');
            cy.get('#bcu-cancel').click();
            cy.get('#bcu-result').should('be.empty')
              .and('match', ':empty');
        });
    });
});
