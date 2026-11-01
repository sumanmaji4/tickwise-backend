package com.tickwise.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmailParsingTest {

    @Nested
    class ReplyAddresses {

        @Test
        void roundTrip() {
            String address = ReplyAddress.forTicket("acme", 123, "inbound.tickwise.test");
            assertThat(address).isEqualTo("acme+t123@inbound.tickwise.test");
            assertThat(ReplyAddress.parse(address, "inbound.tickwise.test"))
                    .contains(new ReplyAddress.Parsed("acme", 123L));
        }

        @Test
        void plainTenantAddressOpensATicket() {
            assertThat(ReplyAddress.parse("Acme@Inbound.Tickwise.Test", "inbound.tickwise.test"))
                    .contains(new ReplyAddress.Parsed("acme", null));
        }

        @ParameterizedTest
        @CsvSource({"acme@other.test", "acme+x1@inbound.tickwise.test", "-acme@inbound.tickwise.test",
                "@inbound.tickwise.test", "acme+t@inbound.tickwise.test", "acme@sub.inbound.tickwise.test"})
        void rejectsForeignOrMalformedAddresses(String address) {
            assertThat(ReplyAddress.parse(address, "inbound.tickwise.test")).isEmpty();
        }
    }

    @Nested
    class AutoReplies {

        @ParameterizedTest
        @CsvSource({"Auto-Submitted, auto-replied", "auto-submitted, auto-generated", "X-Autoreply, yes",
                "Precedence, bulk", "Precedence, auto_reply", "List-Id, <news.example.com>"})
        void headersMarkAutomatedMail(String header, String value) {
            assertThat(AutoReplyDetector.detect(Map.of(header, value), "pat@customer.test", "hello")).isPresent();
        }

        @Test
        void autoSubmittedNoIsAHuman() {
            assertThat(AutoReplyDetector.detect(Map.of("Auto-Submitted", "no"), "pat@customer.test", "hello")).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({"Out of Office: back Monday", "Automatic reply: Your request", "Auto: hello", "Abwesenheitsnotiz",
                "Réponse automatique", "Undeliverable: [#4] hi", "Delivery Status Notification (Failure)"})
        void outOfOfficeSubjects(String subject) {
            assertThat(AutoReplyDetector.detect(Map.of(), "pat@customer.test", subject)).isPresent();
        }

        @ParameterizedTest
        @CsvSource({"MAILER-DAEMON@mx.example.com", "noreply@shop.test", "no-reply@shop.test", "postmaster@x.test"})
        void automatedSenders(String from) {
            assertThat(AutoReplyDetector.detect(Map.of(), from, "hello")).isPresent();
        }

        @Test
        void ordinaryMailIsNotFlagged() {
            assertThat(AutoReplyDetector.detect(Map.of("X-Auto-Response-Suppress", "All"), "pat@customer.test",
                    "Re: [#4] The office printer is out of toner")).isEmpty();
        }
    }

    @Nested
    class Webhooks {

        private final byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);

        @Test
        void validHmacIsAccepted() {
            WebhookVerifier verifier = new WebhookVerifier(new EmailProperties.Webhook("hmac", "X-Sig", "s3cret"));
            assertThat(verifier.verify(body, WebhookVerifier.sign("s3cret", body))).isTrue();
        }

        @Test
        void tamperedBodyOrWrongSecretIsRejected() {
            WebhookVerifier verifier = new WebhookVerifier(new EmailProperties.Webhook("hmac", "X-Sig", "s3cret"));
            String signature = WebhookVerifier.sign("s3cret", body);
            assertThat(verifier.verify("{\"items\":[1]}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
            assertThat(verifier.verify(body, WebhookVerifier.sign("other", body))).isFalse();
            assertThat(verifier.verify(body, "sha256=nothex")).isFalse();
            assertThat(verifier.verify(body, null)).isFalse();
        }

        @Test
        void missingSecretFailsClosed() {
            WebhookVerifier verifier = new WebhookVerifier(new EmailProperties.Webhook("hmac", "X-Sig", ""));
            assertThat(verifier.verify(body, WebhookVerifier.sign("anything", body))).isFalse();
            assertThat(verifier.verify(body, "sha256=")).isFalse();
        }

        @Test
        void tokenMode() {
            WebhookVerifier verifier = new WebhookVerifier(new EmailProperties.Webhook("token", "X-Token", "s3cret"));
            assertThat(verifier.verify(body, "s3cret")).isTrue();
            assertThat(verifier.verify(body, "s3cre")).isFalse();
        }
    }

    @Nested
    class BrevoPayloads {

        private final ObjectMapper json = new ObjectMapper();

        @Test
        void mapsFieldsAndPrefersTheExtractedReply() throws Exception {
            var item = json.readTree("""
                    {
                      "MessageId": "<abc@mail.customer.test>",
                      "InReplyTo": "<out-1@tickwise.test>",
                      "From": {"Name": "Pat", "Address": "pat@customer.test"},
                      "To": [{"Name": null, "Address": "acme+t7@inbound.tickwise.test"}],
                      "Cc": [{"Address": "boss@customer.test"}],
                      "Subject": "Re: [#7] Printer",
                      "RawTextBody": "Still broken\\n\\n> On Monday you wrote...",
                      "ExtractedMarkdownMessage": "Still broken",
                      "Headers": {"References": "<out-0@tickwise.test> <out-1@tickwise.test>", "Auto-Submitted": ["no"]},
                      "Attachments": [{"Name": "log.txt", "ContentType": "text/plain", "ContentLength": 5, "DownloadToken": "tok"}]
                    }""");

            ParsedEmail email = BrevoInboundMapper.map(item);

            assertThat(email.messageId()).isEqualTo("<abc@mail.customer.test>");
            assertThat(email.threadMessageIds()).containsExactly("<out-1@tickwise.test>", "<out-0@tickwise.test>");
            assertThat(email.fromAddress()).isEqualTo("pat@customer.test");
            assertThat(email.recipients()).containsExactly("acme+t7@inbound.tickwise.test", "boss@customer.test");
            assertThat(email.body()).isEqualTo("Still broken");
            assertThat(email.headers()).containsEntry("Auto-Submitted", "no");
            assertThat(email.attachments()).singleElement().satisfies(a -> {
                assertThat(a.filename()).isEqualTo("log.txt");
                assertThat(a.downloadToken()).isEqualTo("tok");
            });
        }

        @Test
        void fallsBackToHtmlConvertedToText() throws Exception {
            var item = json.readTree("""
                    {"MessageId": "id-without-brackets@x", "From": {"Address": "a@b.test"},
                     "RawHtmlBody": "<p>Hello <b>there</b></p><script>alert(1)</script><p>Line two<br>Line three</p>"}""");

            ParsedEmail email = BrevoInboundMapper.map(item);

            assertThat(email.messageId()).isEqualTo("<id-without-brackets@x>");
            assertThat(email.body()).isEqualTo("Hello there\nLine two\nLine three");
        }
    }

    @Nested
    class Html {

        @Test
        void stripsMarkupScriptsAndStyles() {
            assertThat(HtmlToText.convert("<html><head><style>p{}</style></head><body><div>A</div><div>B &amp; C</div>"
                    + "<img src=x onerror=alert(1)></body></html>")).isEqualTo("A\nB & C");
        }

        @Test
        void emptyInput() {
            assertThat(HtmlToText.convert(null)).isEmpty();
            assertThat(HtmlToText.convert("  ")).isEmpty();
        }
    }

    @Test
    void attachmentFilenamesAreSanitised() {
        assertThat(InboundEmailProcessor.safeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(InboundEmailProcessor.safeFilename("C:\\Users\\x\\report.pdf")).isEqualTo("report.pdf");
        assertThat(InboundEmailProcessor.safeFilename("a\"b\r\n.txt")).isEqualTo("a_b__.txt");
        assertThat(InboundEmailProcessor.safeFilename("..")).isEqualTo("attachment");
        assertThat(InboundEmailProcessor.safeFilename(null)).isEqualTo("attachment");
    }
}
