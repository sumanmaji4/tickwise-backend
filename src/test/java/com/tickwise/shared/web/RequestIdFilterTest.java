package com.tickwise.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesWellFormedInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "lb-1234.abc_DEF");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, capture(seenInChain));

        assertThat(seenInChain.get()).isEqualTo("lb-1234.abc_DEF");
        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("lb-1234.abc_DEF");
    }

    @Test
    void replacesIdThatCouldInjectIntoLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "abc\n2026-01-01 ERROR forged line");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, capture(seenInChain));

        assertThat(seenInChain.get()).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(seenInChain.get());
    }

    @Test
    void generatesIdWhenAbsentAndClearsMdcAfterwards() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, capture(seenInChain));

        assertThat(seenInChain.get()).isNotBlank();
        assertThat(MDC.get(RequestIds.REQUEST_ID)).isNull();
    }

    @Test
    void rejectsOverlongId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "a".repeat(65));
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seenInChain));

        assertThat(seenInChain.get()).hasSize(36);
    }

    private static FilterChain capture(AtomicReference<String> target) {
        return (req, res) -> target.set(MDC.get(RequestIds.REQUEST_ID));
    }
}
