package com.projectsknowledge.business.source.service;

import com.projectsknowledge.business.source.schema.response.DtoSourceContent;

/** Business boundary for source retrieval service. */
public interface SourceRetrievalService {
    DtoSourceContent content(String repositoryId, String filePath, int startLine, int endLine);
}
