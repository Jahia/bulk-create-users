package org.jahia.community.bulkcreateusers.users;

import au.com.bytecode.opencsv.CSVReader;
import org.jahia.community.bulkcreateusers.users.management.CsvFile;
import org.jahia.services.content.*;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.binding.message.MessageBuilder;
import org.springframework.binding.message.MessageContext;

import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jahia.api.Constants;
import org.jahia.services.content.decorator.JCRGroupNode;
import org.jahia.services.usermanager.JahiaGroupManagerService;

public class UsersFlowHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersFlowHandler.class);
    private String siteKey;
    private JahiaUserManagerService userManagerService;
    private JahiaGroupManagerService groupManagerService;

    public void initRealm(RenderContext renderContext) throws RepositoryException {
        final JCRNodeWrapper mainNode = renderContext.getMainResource().getNode();
        if (mainNode != null && mainNode.isNodeType("jnt:virtualsite")) {
            siteKey = ((JCRSiteNode) mainNode).getSiteKey();
        }
    }

    private Properties buildProperties(List<String> headerElementList, List<String> lineElementList) {
        final Properties result = new Properties();
        int ignoreGroupsElement = 0;
        if (headerElementList.contains("groups")) {
            ignoreGroupsElement = 1;
        }
        for (int i = 0; i < headerElementList.size() - ignoreGroupsElement; i++) {
            final String currentHeader = headerElementList.get(i);
            final String currentValue = lineElementList.get(i);
            if (!Constants.NODENAME.equals(currentHeader) && !JCRUserNode.J_PASSWORD.equals(currentHeader)) {
                result.setProperty(currentHeader.trim(), currentValue);
            }
        }
        return result;
    }

    public boolean bulkAddUser(final CsvFile csvFile, final MessageContext context) throws RepositoryException {
        LOGGER.info("Bulk adding users");

        long timer = System.currentTimeMillis();
        boolean hasErrors = JCRTemplate.getInstance().doExecuteWithSystemSession((JCRSessionWrapper session) -> {
            boolean hasErrors1 = false;
            try (final CSVReader csvReader = new CSVReader(new InputStreamReader(csvFile.getMultipartFile().getInputStream(), StandardCharsets.UTF_8),
                    csvFile.getCsvSeparator().charAt(0), '"')) {
                final String[] headerElements = csvReader.readNext();
                final List<String> headerElementList = Arrays.asList(headerElements);
                int userNamePos = headerElementList.indexOf(Constants.NODENAME);
                int passwordPos = headerElementList.indexOf(JCRUserNode.J_PASSWORD);
                int groupPos = headerElementList.indexOf("groups");
                if ((userNamePos < 0) || (passwordPos < 0)) {
                    context.addMessage(new MessageBuilder().error().code(
                            "bulk-create-users.users.bulk.errors.missing.mandatory").args(new String[]{Constants.NODENAME, JCRUserNode.J_PASSWORD}).build());
                    return false;
                }
                String[] lineElements;
                int batchLineNumber = 0;
                int lineNumber = 0;
                while ((lineElements = csvReader.readNext()) != null) {
                    if (batchLineNumber == 100) {
                        batchLineNumber = 0;
                        session.save();
                    }
                    final List<String> lineElementList = Arrays.asList(lineElements);
                    final Properties properties = buildProperties(headerElementList, lineElementList);
                    final String userName = lineElementList.get(userNamePos);
                    final String password = lineElementList.get(passwordPos);
                    if (userManagerService.userExists(userName, siteKey)) {
                        context.addMessage(new MessageBuilder().error().code(
                                "bulk-create-users.users.bulk.errors.user.already.exists").arg(userName).build());
                        hasErrors1 = true;
                    } else if (userManagerService.isUsernameSyntaxCorrect(userName)) {
                        final JCRUserNode jahiaUser = userManagerService.createUser(userName, siteKey, password, properties, session);
                        if (jahiaUser != null) {
                            context.addMessage(new MessageBuilder().info().code(
                                    "bulk-create-users.users.bulk.user.creation.successful").arg(userName).build());
                        } else {
                            context.addMessage(new MessageBuilder().error().code(
                                    "bulk-create-users.users.bulk.errors.user.creation.failed").arg(userName).build());
                            hasErrors1 = true;
                        }
                        if (groupPos > 0) {
                            final String groupsValue = lineElementList.get(groupPos);
                            for (String group : groupsValue.split("\\$")) {
                                final JCRGroupNode jahiaGroup = groupManagerService.lookupGroup(siteKey, group, session);
                                if (jahiaGroup != null) {
                                    jahiaGroup.addMember(jahiaUser);
                                }
                            }
                        }
                    } else {
                        LOGGER.error(String.format("Error at line %d", lineNumber));
                        context.addMessage(new MessageBuilder().error().code(
                                "bulk-create-users.users.bulk.errors.user.skipped").build());
                        hasErrors1 = true;
                    }
                    batchLineNumber++;
                    lineNumber++;
                }
                session.save();
            } catch (IOException e) {
                LOGGER.error(e.getMessage(), e);
            }
            return hasErrors1;
        });

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Batch user create took %s ms", System.currentTimeMillis() - timer));
        }
        csvFile.setMultipartFile(null);
        return !hasErrors;
    }

    public CsvFile initCSVFile() {
        final CsvFile csvFile = new CsvFile();
        csvFile.setCsvSeparator(",");
        return csvFile;
    }

    @Autowired
    public void setUserManagerService(JahiaUserManagerService userManagerService) {
        this.userManagerService = userManagerService;
    }

    @Autowired
    public void setGroupManagerService(JahiaGroupManagerService groupManagerService) {
        this.groupManagerService = groupManagerService;
    }
}
