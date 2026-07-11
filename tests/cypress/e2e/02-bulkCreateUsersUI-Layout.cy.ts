describe('Bulk Create Users — UI Layout', () => {
    const ADMIN_ROUTE = '/jahia/administration/bulkCreateUsers';

    // See SUPPORT-646 Stage 6: the previous deleteUser.graphql cleanup used a raw JCR
    // mutateNodesByQuery delete, which Jahia rejects for jnt:user nodes
    // (AccessDeniedException), silently swallowed by failOnStatusCode: false. Use the
    // proper JahiaUserManagerService-backed cleanup script instead.
    before(() => {
        cy.login();
        cy.executeGroovy('groovy/deleteAllTestUsers.groovy');
    });

    after(() => {
        cy.executeGroovy('groovy/deleteAllTestUsers.groovy');
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
            cy.get('[id=bcu-toggle-requirements]').click();
            cy.get('[id=bcu-requirements-box]').should('be.visible');
            cy.get('[id=bcu-toggle-requirements]').click();
            cy.get('[id=bcu-requirements-box]').should('not.be.visible');
        });
    });
});
