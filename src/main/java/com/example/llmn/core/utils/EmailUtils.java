package com.example.llmn.core.utils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailUtils {

    private final TemplateEngine templateEngine;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String SERVICE_MAIL_ACCOUNT;

    private static final String UTF_EIGHT_ENCODING = "UTF-8";

    @Async
    public void sendMail(String toEmail, String subject, String templateName, Map<String, Object> templateModel) {
        try {
            MimeMessage message = createMimeMessage(toEmail, subject, templateName, templateModel);
            mailSender.send(message);
        } catch (MessagingException e){
            log.info(toEmail + "로의 메일 전송에 실패했습니다");
        }
    }

    public MimeMessage createMimeMessage(String toEmail, String subject, String templateName, Map<String, Object> templateModel) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, UTF_EIGHT_ENCODING);

        helper.setFrom(SERVICE_MAIL_ACCOUNT);
        helper.setTo(toEmail);
        helper.setSubject(subject);

        String htmlContent = generateHtmlContent(templateName, templateModel);
        helper.setText(htmlContent, true);

        return message;
    }

    public String generateHtmlContent(String templateName, Map<String, Object> templateModel) {
        Context context = new Context();
        templateModel.forEach(context::setVariable);
        return templateEngine.process(templateName, context);
    }
}
