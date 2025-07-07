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

        if (!ServletFileUpload.isMultipartContent(req)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not a multipart request");
            return;
        }

        try {
            String siteKey = null;
            CsvFile csvFile = new CsvFile();
            List<FileItem> items = new ServletFileUpload(new DiskFileItemFactory()).parseRequest(req);

            for (FileItem item : items) {
                if (item.isFormField()) {
                    switch (item.getFieldName()) {
                        case "siteKey":
                            siteKey = item.getString().isEmpty() ? null : item.getString();
                            break;
                        default:
                            csvFile.setCsvSeparator(item.getString() != null ? item.getString() : ",");
                    }
                } else {
                    csvFile.setCsvFile(item.getInputStream());
                }
            }

            boolean success = usersHandler.bulkAddUser(csvFile, siteKey);
            resp.setStatus(success ? HttpServletResponse.SC_OK : HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\": " + success + ", \"message\": \"" +
                    (success ? "Users uploaded successfully" : "Some errors occurred during upload") + "\"}");

        } catch (FileUploadException | RepositoryException e) {
            logger.error("Error during file upload or repository operation", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Upload failed");
        }
    }
}