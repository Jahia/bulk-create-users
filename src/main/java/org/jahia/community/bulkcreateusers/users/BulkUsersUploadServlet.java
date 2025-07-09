package org.jahia.community.bulkcreateusers.users;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.jahia.community.bulkcreateusers.users.management.CsvFile;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@Component(
        service = {javax.servlet.http.HttpServlet.class, javax.servlet.Servlet.class},
        property = {"alias=/bulk-users-upload", "osgi.http.whiteboard.servlet.asyncSupported=true"},
        immediate = true
)
@MultipartConfig
public class BulkUsersUploadServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(BulkUsersUploadServlet.class);

    @Reference
    private UsersHandler usersHandler;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        try {
            String siteKey = null;
            CsvFile csvFile = new CsvFile();
            List<FileItem> items = new ServletFileUpload(new DiskFileItemFactory()).parseRequest(req);

            siteKey = processFileItems(items, csvFile);

            boolean success = usersHandler.bulkAddUser(csvFile, siteKey);
            writeJsonResponse(resp, success);

        } catch (FileUploadException | RepositoryException e) {
            logger.error("Error during file upload or repository operation", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "User creation failed");
        }
    }

    private String processFileItems(List<FileItem> items, CsvFile csvFile) throws IOException {
        String siteKey = null;
        for (FileItem item : items) {
            if (item.isFormField()) {
                if ("siteKey".equals(item.getFieldName())) {
                    siteKey = item.getString().isEmpty() ? null : item.getString();
                } else {
                    csvFile.setCsvSeparator(item.getString() != null ? item.getString() : ",");
                }
            } else {
                csvFile.setUploadedFile(item.getInputStream());
            }
        }
        return siteKey;
    }

    private void writeJsonResponse(HttpServletResponse resp, boolean success) throws IOException {
        resp.setStatus(success ? HttpServletResponse.SC_OK : HttpServletResponse.SC_BAD_REQUEST);
        resp.getWriter().write("{\"success\": " + success + ", \"message\": \"" +
                (success ? "Users created successfully." : "Some errors occurred during user creation.") + "\"}");
    }
}