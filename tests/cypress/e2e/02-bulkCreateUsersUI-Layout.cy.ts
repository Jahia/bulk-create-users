import {DocumentNode} from 'graphql';

describe('Bulk Create Users — UI Layout', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteUser: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');

    const ADMIN_ROUTE = '/jahia/administration/bulkCreateUsers';

    before(() => {
        cy.login();
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
    });

    after(() => {
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
    });

    // ─── Layout ──────────────────────────────────────────────────────────────────

    describe('Admin page layout', () => {
        it('renders the admin page without errors', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('[class*="bcu_root"]').should('exist');
        });

        it('displays the page title', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.contains('Bulk Create Users').should('be.visible');
        });

        it('renders the CSV file input', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-csv-file').should('exist');
        });

        it('renders the delimiter input with default comma', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-delimiter').should('have.value', ',');
        });

        it('renders the Submit button disabled when no file selected', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-submit').should('be.disabled');
        });

        it('renders the Cancel button enabled', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-cancel').should('not.be.disabled');
        });

        it('shows CSV format requirements when toggled', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-toggle-requirements').click();
            cy.contains('j:nodename').should('be.visible');
            cy.contains('j:password').should('be.visible');
        });

        it('hides requirements again when toggled a second time', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-toggle-requirements').click();
            cy.get('#bcu-toggle-requirements').click();
            cy.contains('j:nodename').should('not.exist');
        });
    });
});
