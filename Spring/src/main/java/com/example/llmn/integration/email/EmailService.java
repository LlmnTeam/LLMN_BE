package com.example.llmn.integration.email;

import com.example.llmn.integration.email.model.TemplateModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.example.llmn.common.constants.GlobalConstants.CHAR_SET;
import static com.example.llmn.common.constants.GlobalConstants.UTF_EIGHT_ENCODING;
import static com.example.llmn.integration.email.EmailConstants.MODEL_KEY_CODE;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final TemplateEngine templateEngine;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String serviceMailAccount;

    @Async
    public void sendMail(String toEmail, String subject, String templateName, TemplateModel templateModel) {
        try {
            MimeMessage message = createMimeMessage(toEmail, subject, templateName, templateModel);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("{}로의 메일 전송에 실패했습니다", toEmail);
        }
    }

    public static String generateVerificationCode() {
        SecureRandom random = new SecureRandom();
        return IntStream.range(0, 8) // 8자리
                .map(i -> random.nextInt(CHAR_SET.length()))
                .mapToObj(CHAR_SET::charAt)
                .map(Object::toString)
                .collect(Collectors.joining());
    }

    private MimeMessage createMimeMessage(String toEmail, String subject, String templateName, TemplateModel templateModel) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();

        MimeMessageHelper helper = new MimeMessageHelper(message, true, UTF_EIGHT_ENCODING);
        helper.setFrom(serviceMailAccount);
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(createHtmlText(templateName, templateModel), true);

        return message;
    }

    private String createHtmlText(String templateName, TemplateModel templateModel) {
        Map<String, Object> modelMap = convertTemplateModelToMap(templateModel);

        Context context = new Context();
        modelMap.forEach(context::setVariable);
        return templateEngine.process(templateName, context);
    }

    private Map<String, Object> convertTemplateModelToMap(TemplateModel templateModel) {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.convertValue(templateModel, new TypeReference<>() {});
    }
}
