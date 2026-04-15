package ar.gob.smn.weather;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

final class MailSender {

    private static final Logger LOG = Logger.getLogger(MailSender.class.getName());

    private final Config config;

    MailSender(Config config) {
        this.config = config;
    }

    void send(List<String> to, String subject, String textBody) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.smtpHost());
        props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        props.put("mail.smtp.auth", "true");

        if (config.smtpSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", config.smtpHost());
            props.put("mail.smtp.socketFactory.port", String.valueOf(config.smtpPort()));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
        } else if (config.smtpStartTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        Session session = Session.getInstance(props, null);

        if (to.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient required");
        }
        InternetAddress[] recipients = new InternetAddress[to.size()];
        for (int i = 0; i < to.size(); i++) {
            recipients[i] = new InternetAddress(to.get(i).trim());
        }

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(config.fromAddress()));
        msg.setRecipients(Message.RecipientType.TO, recipients);
        msg.setSubject(subject, "UTF-8");
        msg.setText(textBody, "UTF-8");

        String mode = config.smtpSsl() ? "SSL" : (config.smtpStartTls() ? "STARTTLS" : "plain");
        LOG.info(() -> "Sending mail via " + config.smtpHost() + ":" + config.smtpPort()
                + " (" + mode + ") from " + config.fromAddress() + " to " + to);

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(config.smtpHost(), config.smtpPort(), config.smtpUser(), config.smtpPassword());
            transport.sendMessage(msg, msg.getAllRecipients());
        }
    }
}
