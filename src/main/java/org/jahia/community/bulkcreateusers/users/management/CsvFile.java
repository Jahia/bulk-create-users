package org.jahia.community.bulkcreateusers.users.management;

import java.io.InputStream;

public class CsvFile {
    private InputStream csvFile;
    private String csvSeparator;

    public InputStream getCsvFile() {
        return csvFile;
    }

    public void setCsvFile(InputStream csvFile) {
        this.csvFile = csvFile;
    }

    public String getCsvSeparator() {
        return csvSeparator;
    }

    public void setCsvSeparator(String csvSeparator) {
        this.csvSeparator = csvSeparator;
    }
}