package org.jahia.community.bulkcreateusers.users.management;

import org.jahia.utils.i18n.Messages;
import org.springframework.binding.message.MessageBuilder;
import org.springframework.binding.validation.ValidationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.multipart.MultipartFile;

public class CsvFile {

    private String csvSeparator;
    private MultipartFile multipartFile;

    public String getCsvSeparator() {
        return csvSeparator;
    }

    public void setCsvSeparator(String csvSeparator) {
        this.csvSeparator = csvSeparator;
    }

    public MultipartFile getMultipartFile() {
        return multipartFile;
    }

    public void setMultipartFile(MultipartFile multipartFile) {
        this.multipartFile = multipartFile;
    }

    public void validateBulkCreateUser(ValidationContext context) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            context.getMessageContext().addMessage(new MessageBuilder().error().source("csvFile")
                    .defaultText(Messages.get("resources.JahiaSiteSettings", "bulk-create-users.users.bulk.errors.missing.import", LocaleContextHolder.getLocale())).build());
        }
    }
}
