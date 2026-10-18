package com.techinterviewer.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /** Runs the filter and returns the response plus the MDC value that was visible inside the chain. */
    private record Outcome(MockHttpServletResponse response, String mdcInsideChain) {
    }

    private Outcome run(String requestId) throws Exception {
        return runWithHeaders(requestId == null ? new String[0] : new String[] {requestId});
    }

    private Outcome runWithHeaders(String... requestIds) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (String requestId : requestIds) {
            request.addHeader(CorrelationIdFilter.HEADER, requestId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                seen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        }));
        return new Outcome(response, seen.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "req-2026.10.05_a1", "0123456789012345678901234567890123456789012345678901234567890123"})
    void validIdIsEchoedAndUsedAsTraceId(String id) throws Exception {
        Outcome outcome = run(id);

        assertThat(outcome.response().getHeader(CorrelationIdFilter.HEADER)).isEqualTo(id);
        assertThat(outcome.mdcInsideChain()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "short",
        "01234567890123456789012345678901234567890123456789012345678901234",
        "has spaces in it",
        "semi;colon-id-1234",
        "<script>alert(1)</script>",
        "unicode-é-id-1234"
    })
    void invalidIdIsReplacedByAGeneratedOne(String id) throws Exception {
        Outcome outcome = run(id);

        String echoed = outcome.response().getHeader(CorrelationIdFilter.HEADER);
        assertThat(echoed).isNotEqualTo(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(outcome.mdcInsideChain()).isEqualTo(echoed);
    }

    @Test
    void missingIdIsGenerated() throws Exception {
        Outcome outcome = run(null);

        assertThat(outcome.response().getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
        assertThat(outcome.mdcInsideChain()).isEqualTo(outcome.response().getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    void whenTheHeaderIsRepeatedTheFirstValidValueDecides() throws Exception {
        Outcome outcome = runWithHeaders("first-request-id-1", "second-request-id-2");

        assertThat(outcome.response().getHeader(CorrelationIdFilter.HEADER)).isEqualTo("first-request-id-1");
        assertThat(outcome.mdcInsideChain()).isEqualTo("first-request-id-1");
    }

    @Test
    void anInvalidFirstValueIsNeverReplacedByALaterValidOne() throws Exception {
        Outcome outcome = runWithHeaders("bad id", "second-request-id-2");

        String echoed = outcome.response().getHeader(CorrelationIdFilter.HEADER);
        assertThat(echoed).isNotEqualTo("second-request-id-2").matches("[0-9a-f-]{36}");
        assertThat(outcome.response().getHeaders(CorrelationIdFilter.HEADER)).hasSize(1);
    }

    @Test
    void aCommaJoinedValueIsInvalidAndReplaced() throws Exception {
        Outcome outcome = run("first-request-id-1, second-request-id-2");

        assertThat(outcome.response().getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }

    @Test
    void theResponseCarriesExactlyOneRequestIdHeader() throws Exception {
        Outcome outcome = runWithHeaders("first-request-id-1", "second-request-id-2");

        assertThat(outcome.response().getHeaders(CorrelationIdFilter.HEADER)).containsExactly("first-request-id-1");
    }

    @Test
    void mdcIsClearedAfterTheRequest() throws Exception {
        run("abcdefgh");

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcIsClearedWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
