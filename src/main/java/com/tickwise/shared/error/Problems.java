package com.tickwise.shared.error;

import com.tickwise.shared.web.RequestIds;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds problem details with the fields every Tickwise error carries. */
public final class Problems {

    private Problems() {}

    public static ProblemDetail of(HttpStatusCode status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return enrich(problem);
    }

    /** Adds the correlation id so a client-reported error can be found in the logs. */
    public static ProblemDetail enrich(ProblemDetail problem) {
        String requestId = RequestIds.current();
        if (requestId != null) {
            problem.setProperty(RequestIds.REQUEST_ID, requestId);
        }
        return problem;
    }
}
