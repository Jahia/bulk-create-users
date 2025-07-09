package org.jahia.community.bulkcreateusers.users.management;

import java.io.InputStream;

public class CsvFile {
    private InputStream uploadedFile;
    private String csvSeparator;

    public InputStream getUploadedFile() {
        return uploadedFile;
    }

    public void setUploadedFile(InputStream uploadedFile) {
        this.uploadedFile = uploadedFile;
    }

    public String getCsvSeparator() {
        return csvSeparator;
    }

    public void setCsvSeparator(String csvSeparator) {
        this.csvSeparator = csvSeparator;
    }
}