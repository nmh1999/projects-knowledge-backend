package com.projectsknowledge.business.knowledge.service;

import com.projectsknowledge.business.knowledge.schema.request.ReqIntegrationDetails;
import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;

/** Business boundary for question ask service. */
public interface QuestionAskService {
    DtoKnowledgeAnswer ask(ReqQuestion request);

    DtoKnowledgeAnswer refresh(ReqQuestion request);

    DtoKnowledgeAnswer explainIntegration(ReqIntegrationDetails request);

    DtoKnowledgeAnswer refreshIntegration(ReqIntegrationDetails request);
}
