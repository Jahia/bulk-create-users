package org.jahia.community.bulkcreateusers.users.management;

import java.io.Serializable;
import org.jahia.utils.i18n.Messages;
import org.springframework.binding.message.MessageBuilder;
import org.springframework.binding.validation.ValidationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.multipart.MultipartFile;

public class CsvFile implements Serializable{

    private static final long serialVersionUID = 5506459343721460018L;

    private String csvSeparator;
    private MultipartFile csvFile;

    public String getCsvSeparator() {
        return csvSeparator;
    }

    public void setCsvSeparator(String csvSeparator) {
        this.csvSeparator = csvSeparator;
    }

    public MultipartFile getCsvFile() {
        return csvFile;
    }

    public void setCsvFile(MultipartFile csvFile) {
        this.csvFile = csvFile;
    }
    
    public void validateBulkCreateUser(ValidationContext context) {
        if (csvFile == null || csvFile.isEmpty()) {
            context.getMessageContext().addMessage(new MessageBuilder().error().source("csvFile")
                    .defaultText(Messages.get("resources.JahiaSiteSettings", "siteSettings.users.bulk.errors.missing.import", LocaleContextHolder.getLocale())).build());
        }
    }
}
