package server;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Gmail SMTP sender using only JDK classes (SMTPS over SSL, port 465).
 * The password must be a Google App Password, not the normal Gmail password.
 */
public final class EmailService {
    private final String gmail;
    private final String appPassword;

    public EmailService(String gmail, String appPassword) {
        this.gmail = gmail == null ? "" : gmail.trim();
        this.appPassword = appPassword == null ? "" : appPassword.replace(" ", "").trim();
    }

    public boolean isConfigured() {
        return !gmail.isBlank() && gmail.contains("@") && !appPassword.isBlank();
    }

    public void sendPasswordResetCode(String recipientEmail, String fullName, String code, int expiryMinutes) throws IOException {
        if (!isConfigured()) throw new IllegalStateException("Server chưa cấu hình Gmail/App Password để gửi OTP");
        String subject = "Smart Classroom - Ma xac minh dat lai mat khau";
        String body = "Xin chao " + (fullName == null || fullName.isBlank() ? "ban" : fullName) + ",\n\n"
                + "Ma xac minh Smart Classroom cua ban la: " + code + "\n\n"
                + "Ma co hieu luc trong " + expiryMinutes + " phut va chi dung duoc mot lan.\n"
                + "Neu ban khong yeu cau dat lai mat khau, hay bo qua email nay.\n\n"
                + "Smart Classroom";
        send(recipientEmail, subject, body);
    }

    public void send(String recipientEmail, String subject, String body) throws IOException {
        SSLSocketFactory factory = createSslSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("smtp.gmail.com", 465)) {
            socket.setSoTimeout(12000);
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                expect(in, 220);
                command(out, in, "EHLO smart-classroom", 250);
                command(out, in, "AUTH LOGIN", 334);
                command(out, in, b64(gmail), 334);
                command(out, in, b64(appPassword), 235);
                command(out, in, "MAIL FROM:<" + gmail + ">", 250);
                command(out, in, "RCPT TO:<" + recipientEmail + ">", 250, 251);
                command(out, in, "DATA", 354);

                String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
                String encodedBody = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(body.getBytes(StandardCharsets.UTF_8));
                out.write("From: Smart Classroom <" + gmail + ">\r\n");
                out.write("To: <" + recipientEmail + ">\r\n");
                out.write("Subject: " + encodedSubject + "\r\n");
                out.write("MIME-Version: 1.0\r\n");
                out.write("Content-Type: text/plain; charset=UTF-8\r\n");
                out.write("Content-Transfer-Encoding: base64\r\n");
                out.write("\r\n");
                out.write(encodedBody.replace("\n.", "\n.."));
                out.write("\r\n.\r\n");
                out.flush();
                expect(in, 250);
                try { command(out, in, "QUIT", 221); } catch (Exception ignored) {}
            }
        }
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void command(BufferedWriter out, BufferedReader in, String cmd, int... expected) throws IOException {
        out.write(cmd + "\r\n");
        out.flush();
        Reply r = readReply(in);
        for (int code : expected) if (r.code == code) return;
        throw new IOException("SMTP loi " + r.code + ": " + r.text);
    }

    private static void expect(BufferedReader in, int... expected) throws IOException {
        Reply r = readReply(in);
        for (int code : expected) if (r.code == code) return;
        throw new IOException("SMTP loi " + r.code + ": " + r.text);
    }

    private static Reply readReply(BufferedReader in) throws IOException {
        String first = in.readLine();
        if (first == null || first.length() < 3) throw new EOFException("SMTP khong phan hoi");
        int code;
        try { code = Integer.parseInt(first.substring(0, 3)); }
        catch (NumberFormatException e) { throw new IOException("SMTP phan hoi khong hop le: " + first); }
        StringBuilder all = new StringBuilder(first);
        if (first.length() > 3 && first.charAt(3) == '-') {
            String line;
            do {
                line = in.readLine();
                if (line == null) break;
                all.append(" | ").append(line);
            } while (!(line.length() >= 4 && line.startsWith(String.valueOf(code)) && line.charAt(3) == ' '));
        }
        return new Reply(code, all.toString());
    }

    /**
     * Trên Windows, Java mặc định thường dùng truststore cacerts riêng. Một số máy có
     * antivirus/proxy kiểm tra TLS và phát hành lại chứng chỉ bằng CA đã được Windows tin cậy,
     * nhưng Java cacerts không biết CA đó -> PKIX path building failed.
     *
     * Dùng Windows-ROOT giúp Java tin cùng kho chứng chỉ mà Windows/Chrome đang dùng.
     * Trên hệ điều hành khác sẽ quay về truststore mặc định của JDK.
     */
    private static SSLSocketFactory createSslSocketFactory() throws IOException {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
                windowsRoot.load(null, null);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(windowsRoot);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, tmf.getTrustManagers(), new SecureRandom());
                return context.getSocketFactory();
            }
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        } catch (Exception e) {
            throw new IOException("Khong khoi tao duoc TLS trust store de gui Gmail: " + e.getMessage(), e);
        }
    }

    private record Reply(int code, String text) {}
}
