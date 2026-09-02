package com.projectsknowledge.general.cancellation;

import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import org.springframework.http.HttpStatus;

/** An explicit cancellation is not a model failure and must never populate a cache. */
public final class RequestCancelledException extends KnowledgeException {

    public RequestCancelledException() {
        super(HttpStatus.CONFLICT, ApiErrorCode.REQUEST_CANCELLED, "The request was cancelled.", false);
    }
}
