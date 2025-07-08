package org.jahia.community.bulkcreateusers.users;

import au.com.bytecode.opencsv.CSVReader;
import org.jahia.community.bulkcreateusers.users.management.CsvFile;
import org.jahia.services.content.*;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.content.decorator.JCRGroupNode;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.services.usermanager.JahiaGroupManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.jcr.RepositoryException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.jahia.api.Constants;

@Component(service = UsersHandler.class)
public class UsersHandler implements Serializable {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersHandler.class);
    private static final long serialVersionUID = 4640941698245660627L;
    private transient JahiaUserManagerService userManagerService;
    private transient JahiaGroupManagerService groupManagerService;

    private Properties buildProperties(List<String> headers, List<String> values) {
        Properties props = new Properties();
        for (int i = 0; i < headers.size(); i++) {
            String key = headers.get(i);
            if (!Constants.NODENAME.equals(key) && !JCRUserNode.J_PASSWORD.equals(key) && !"groups".equals(key)) {
                if (i >= values.size() || values.get(i) == null || values.get(i).trim().isEmpty()) {
                    throw new IllegalArgumentException("Empty value for " + key);
                }
                props.setProperty(key.trim(), values.get(i));
            }
        }
        return props;
    }

    private void addUserToGroups(JCRUserNode user, String groups, String siteKey, JCRSessionWrapper session) throws RepositoryException {
        if (groups == null || groups.trim().isEmpty()) return;
        for (String group : groups.split("\\$")) {
            String groupName = group.trim();
            if (!groupName.isEmpty()) {
                JCRGroupNode jahiaGroup = groupManagerService.lookupGroup(siteKey, groupName, session);
                if (jahiaGroup != null) {
                    jahiaGroup.addMember(user);
                    LOGGER.info("Added user {} to group {}", user.getName(), groupName);
                } else {
                    LOGGER.warn("Group {} not found for site {}", groupName, siteKey);
                }
            }
        }
    }

    public boolean bulkAddUser(final CsvFile csvFile, final String siteKey) throws RepositoryException {
        if(siteKey != null) {
            LOGGER.info("Bulk adding users for site: {}", siteKey);
        }
        long start = System.currentTimeMillis();

        boolean hasErrors = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            try (CSVReader reader = new CSVReader(new InputStreamReader(csvFile.getCsvFile(), StandardCharsets.UTF_8), csvFile.getCsvSeparator().charAt(0), '"')) {
                String[] headers = reader.readNext();
                if (headers == null) {
                    LOGGER.error("Missing headers in CSV file");
                    return true;
                }
                List<String> headerList = Arrays.asList(headers);
                int userIdx = headerList.indexOf(Constants.NODENAME);
                int passIdx = headerList.indexOf(JCRUserNode.J_PASSWORD);
                int groupIdx = headerList.indexOf("groups");
                if (userIdx < 0 || passIdx < 0) {
                    LOGGER.error("Invalid CSV file, incorrect required columns");
                    return true;
                }

                String[] row;
                int batch = 0;
                boolean error = false;
                while ((row = reader.readNext()) != null) {
                    if (batch++ == 100) {
                        session.save();
                        batch = 1;
                    }
                    List<String> values = Arrays.asList(row);
                    String user = values.get(userIdx);
                    String pass = values.get(passIdx);
                    String groups = (groupIdx >= 0 && groupIdx < values.size()) ? values.get(groupIdx) : null;
                    Properties props = null;
                    try {
                        props = buildProperties(headerList, values);
                    } catch (IllegalArgumentException ex) {
                        LOGGER.error("Skipping user creation due to invalid data: {}", ex.getMessage());
                        return true;
                    }

                    if (userManagerService.userExists(user, siteKey)) {
                        JCRUserNode existing = userManagerService.lookupUser(user, siteKey, session);
                        addUserToGroups(existing, groups, siteKey, session);
                    } else if (userManagerService.isUsernameSyntaxCorrect(user)) {
                        JCRUserNode created = userManagerService.createUser(user, siteKey, pass, props, session);
                        if (created != null) {
                            LOGGER.info("Created user: {}", user);
                            addUserToGroups(created, groups, siteKey, session);
                        } else {
                            LOGGER.error("Failed to create user: {}", user);
                            error = true;
                        }
                    } else {
                        LOGGER.error("Invalid username syntax: {}", user);
                        error = true;
                    }
                }
                session.save();
                return error;
            } catch ( Exception e) {
                LOGGER.error("Error during bulk user creation", e);
                return true;
            }
        });

        if (hasErrors) {
            LOGGER.error("Errors occurred during bulk user creation");
        } else {
            LOGGER.info("Batch user create took {} ms", System.currentTimeMillis() - start);
        }
        csvFile.setCsvFile(null);
        return !hasErrors;
    }

    public CsvFile initCSVFile() {
        CsvFile csvFile = new CsvFile();
        csvFile.setCsvSeparator(",");
        return csvFile;
    }

    @Reference
    public void setUserManagerService(JahiaUserManagerService userManagerService) {
        this.userManagerService = userManagerService;
    }

    @Reference
    public void setGroupManagerService(JahiaGroupManagerService groupManagerService) {
        this.groupManagerService = groupManagerService;
    }
}