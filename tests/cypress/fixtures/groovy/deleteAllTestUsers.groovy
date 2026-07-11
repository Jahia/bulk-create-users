import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRTemplate
import org.jahia.services.usermanager.JahiaUserManagerService
import javax.jcr.RepositoryException
import javax.jcr.query.Query

/*
 * Test-cleanup helper: deletes every jnt:user node except root/guest via
 * JahiaUserManagerService#deleteUser(path, session) - the same API the bundled
 * @jahia/cypress groovy/admin/deleteUser.groovy uses for a single named user.
 *
 * Stage 6 (SUPPORT-646) found that the previous cleanup mechanism, a raw JCR
 * mutateNodesByQuery GraphQL delete against [jnt:user] nodes, throws
 * javax.jcr.AccessDeniedException: "Deleting a user node is not allowed: <path>"
 * against a real Jahia container - Jahia deliberately blocks deleting jnt:user nodes
 * through the generic content-delete path. Because that GraphQL call was made with
 * failOnStatusCode: false, the error was silently swallowed and cleanup was a no-op,
 * so test users accumulated across tests/specs and caused cascading count-assertion
 * failures. This script replaces that cleanup step everywhere it was used.
 */
JCRTemplate.getInstance().doExecuteWithSystemSession(new JCRCallback() {
    @Override
    Object doInJCR(JCRSessionWrapper session) throws RepositoryException {
        JahiaUserManagerService userManagerService = JahiaUserManagerService.getInstance()
        Query query = session.getWorkspace().getQueryManager().createQuery(
            "SELECT * FROM [jnt:user] as n WHERE n.[j:nodename]<>'root' and n.[j:nodename]<>'guest'",
            Query.JCR_SQL2
        )
        def nodes = query.execute().getNodes()
        while (nodes.hasNext()) {
            def node = nodes.nextNode()
            def path = node.getPath()
            log.info("Deleting test user: " + path)
            userManagerService.deleteUser(path, session)
        }
        session.save()
        return null
    }
})
