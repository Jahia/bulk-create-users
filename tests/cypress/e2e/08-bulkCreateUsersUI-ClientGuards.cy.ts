/**
 * U9-Cypress: the three client-side-only pre-submission guards documented in createUsers.jsx
 * that the API itself does not enforce (the fourth sub-behaviour, Submit disabled while
 * missingRequired.length > 0, is already covered by F11-UIEnforcesAllFour in
 * 04-bulkCreateUsersUI-ColumnSelection.cy.ts and is not re-specified here):
 *  1. A selected file not ending in .csv is rejected client-side (validation.notCsv).
 *  2. A file exceeding the queried maxUploadSize is rejected client-side before any GraphQL call
 *     is ever made (the client-side companion to F6, which tests the server-side rejection of a
 *     payload that got past the client).
 *  3. The delimiter <Input> has a hard maxLength={1}, so a direct keystroke of ";;" can never
 *     produce a 2-character value in the DOM - the UI-side counterpart to D5.
 */
describe('Bulk Create Users — UI client-side guards (U9)', () => {
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

    describe('non-.csv file extension', () => {
        it('rejects a selected file that does not end in .csv and never shows the column section', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/not-a-csv.txt', {force: true});
            cy.get('[id="bcu-message-error"]', {timeout: 5000}).should('be.visible').and('contain', 'valid CSV file');
            cy.get('#bcu-columns').should('not.exist');
            cy.get('#bcu-submit').should('be.disabled');
        });
    });

    describe('client-side file-size pre-check', () => {
        // SUPPORT-646 Stage 6 skipped this test after real investigation (not a first-guess
        // dismissal): building/selecting a file just past the then-advertised maxUploadSize
        // (~100 MiB, the raw jahiaFileUploadMaxSize) hit hard Cypress-runner limitations -
        // `Cypress.Buffer.from()` and then `cy.get(...).selectFile({contents: ...})` itself both
        // threw `RangeError: Invalid array length` on a ~100 MiB in-browser buffer.
        // SUPPORT-646 Stage 7 fix note: `maxUploadSize` is now clamped to the real GraphQL
        // transport ceiling (~20,000,000 characters, see the root-cause comment in
        // 07-bulkCreateUsers-UploadLimit.cy.ts), so the file this test needs to build is now
        // ~20 MB instead of ~100 MB - small enough that both of Stage 6's failure points no
        // longer trigger. Re-enabled.
        it('rejects a file larger than the queried maxUploadSize before any import mutation is fired', () => {
            cy.login();
            cy.intercept('POST', '**/modules/graphql').as('gqlCalls');

            // Read the real configured limit independently (same query the component itself
            // fires on mount) so the oversized fixture is guaranteed to exceed it.
            cy.apollo({
                // eslint-disable-next-line @typescript-eslint/no-var-requires
                query: require('graphql-tag/loader!../fixtures/graphql/query/maxUploadSize.graphql'),
                log: false
            }).its('data.bulkCreateUsers.maxUploadSize').then((limit: number) => {
                cy.visit(ADMIN_ROUTE);

                const oversizedContent = 'j:nodename,j:password,j:firstName,j:lastName\n' +
                    'a'.repeat(Math.max(limit + 1024, 1024));
                cy.get('input[name="csvFile"]').selectFile({
                    contents: new TextEncoder().encode(oversizedContent),
                    fileName: 'oversized.csv',
                    mimeType: 'text/csv'
                }, {force: true});

                cy.get('[id="bcu-message-error"]', {timeout: 5000})
                    .should('be.visible')
                    .and('contain', 'File size must be less than');
                cy.get('#bcu-columns').should('not.exist');

                cy.get('@gqlCalls.all').then((calls: unknown[]) => {
                    const importCalls = (calls as Array<{request: {body?: {operationName?: string}}}>)
                        .filter(call => call.request?.body?.operationName === 'BulkCreateUsersImport');
                    expect(importCalls, 'BulkCreateUsersImport mutation calls').to.have.length(0);
                });
            });
        });
    });

    describe('delimiter hard length cap', () => {
        it('never allows a 2-character delimiter value in the DOM (maxLength={1})', () => {
            cy.login();
            cy.visit(ADMIN_ROUTE);
            cy.get('#bcu-delimiter').clear().type(';;');
            cy.get('#bcu-delimiter').should('have.value', ';');
        });
    });
});
