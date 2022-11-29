package org.jahia.community.bulkcreateusers.users;

import au.com.bytecode.opencsv.CSVReader;
import org.apache.commons.io.IOUtils;
import org.jahia.community.bulkcreateusers.users.management.CsvFile;
import org.jahia.services.content.*;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.pwdpolicy.JahiaPasswordPolicyService;
import org.jahia.services.pwdpolicy.PolicyEnforcementResult;
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
import java.io.Serializable;
import java.util.*;
import org.jahia.services.content.decorator.JCRGroupNode;
import org.jahia.services.usermanager.JahiaGroupManagerService;

public class UsersFlowHandler implements Serializable {

    private static Logger logger = LoggerFactory.getLogger(UsersFlowHandler.class);
    private static final long serialVersionUID = -7240178997123886031L;

    private String siteKey;

    private transient JahiaUserManagerService userManagerService;
    private transient JahiaGroupManagerService groupManagerService;

    public void initRealm(RenderContext renderContext) throws RepositoryException {
        JCRNodeWrapper mainNode = renderContext.getMainResource().getNode();
        if (mainNode != null && mainNode.isNodeType("jnt:virtualsite")) {
            siteKey = ((JCRSiteNode) mainNode).getSiteKey();
        }
    }

    private Properties buildProperties(List<String> headerElementList, List<String> lineElementList) {
        Properties result = new Properties();
        int ignoreGroupsElement = 0;
        if (headerElementList.contains("groups")) {
            ignoreGroupsElement = 1;
        }
        for (int i = 0; i < headerElementList.size() - ignoreGroupsElement; i++) {
            String currentHeader = headerElementList.get(i);
            String currentValue = lineElementList.get(i);
            if (!"j:nodename".equals(currentHeader) && !JCRUserNode.J_PASSWORD.equals(currentHeader)) {
                result.setProperty(currentHeader.trim(), currentValue);
            }
        }
        return result;
    }

    public boolean bulkAddUser(final CsvFile csvFile, final MessageContext context) throws RepositoryException {
        logger.info("Bulk adding users");

        long timer = System.currentTimeMillis();
        boolean hasErrors = JCRTemplate.getInstance().doExecuteWithSystemSession(new JCRCallback<Boolean>() {
            @Override
            public Boolean doInJCR(JCRSessionWrapper session) throws RepositoryException {
                CSVReader csvReader = null;
                boolean hasErrors = false;
                try {

                    csvReader = new CSVReader(new InputStreamReader(csvFile.getCsvFile().getInputStream(), "UTF-8"),
                            csvFile.getCsvSeparator().charAt(0), '"');
                    // the first line contains the column names;
                    String[] headerElements = csvReader.readNext();
                    List<String> headerElementList = Arrays.asList(headerElements);
                    int userNamePos = headerElementList.indexOf("j:nodename");
                    int passwordPos = headerElementList.indexOf(JCRUserNode.J_PASSWORD);
                    int groupPos = headerElementList.indexOf("groups");
                    if ((userNamePos < 0) || (passwordPos < 0)) {
                        context.addMessage(new MessageBuilder().error().code(
                                "bulk-create-users.users.bulk.errors.missing.mandatory").args(new String[]{"j:nodename", JCRUserNode.J_PASSWORD}).build());
                        return false;
                    }

                    String[] lineElements = null;
                    int batchLineNumber = 0;
                    int lineNumber = 0;
                    while ((lineElements = csvReader.readNext()) != null) {
                        if (batchLineNumber == 100) {
                            batchLineNumber = 0;
                            session.save();
                        }

                        List<String> lineElementList = Arrays.asList(lineElements);
                        Properties properties = buildProperties(headerElementList, lineElementList);
                        String userName = lineElementList.get(userNamePos);
                        String password = lineElementList.get(passwordPos);
                        if (userManagerService.userExists(userName, siteKey)) {
                            context.addMessage(new MessageBuilder().error().code(
                                    "bulk-create-users.users.bulk.errors.user.already.exists").arg(userName).build());
                            hasErrors = true;
                        } else if (userManagerService.isUsernameSyntaxCorrect(userName)) {

                            JCRUserNode jahiaUser = userManagerService.createUser(userName, siteKey, password, properties, session);
                            if (jahiaUser != null) {
                                context.addMessage(new MessageBuilder().info().code(
                                        "bulk-create-users.users.bulk.user.creation.successful").arg(userName).build());
                            } else {
                                context.addMessage(new MessageBuilder().error().code(
                                        "bulk-create-users.users.bulk.errors.user.creation.failed").arg(userName).build());
                                hasErrors = true;
                            }
                            if (groupPos > 0) {
//                                session.save();
//                                jahiaUser = userManagerService.lookupUser(userName, siteKey, session);
                                final String groupsValue = lineElementList.get(groupPos);
                                for (String group : groupsValue.split("\\$")) {
                                    final JCRGroupNode jahiaGroup = groupManagerService.lookupGroup(siteKey, group, session);
                                    if (jahiaGroup != null) {
                                        jahiaGroup.addMember(jahiaUser);
                                    }
                                }
//                                session.save();
                            }
                        } else {
                            logger.error(String.format("Error at line %d", lineNumber));
                            context.addMessage(new MessageBuilder().error().code(
                                    "bulk-create-users.users.bulk.errors.user.skipped").build());
                            hasErrors = true;
                        }
                        batchLineNumber++;
                        lineNumber++;
                    }
                    session.save();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                } finally {
                    IOUtils.closeQuietly(csvReader);
                }

                return hasErrors;
            }
        });

        logger.info("Batch user create took " + (System.currentTimeMillis() - timer) + " ms");
        csvFile.setCsvFile(null);
        return !hasErrors;
    }

    public CsvFile initCSVFile() {
        CsvFile csvFile = new CsvFile();
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
