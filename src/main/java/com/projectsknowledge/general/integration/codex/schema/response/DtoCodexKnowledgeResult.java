package com.projectsknowledge.general.integration.codex.schema.response;

import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.ApiInfo;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.DatabaseInfo;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.FlowNode;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.IntegrationInfo;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.RoleInfo;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.ScheduledJobInfo;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.TechnicalDetail;
import com.projectsknowledge.business.knowledge.schema.response.DtoWorkflowDiagram;
import java.util.List;
import lombok.Builder;

@Builder
public record DtoCodexKnowledgeResult(
    String answer,
    String confidence,
    List<String> keyFindings,
    List<String> businessFlow,
    List<FlowNode> technicalFlow,
    List<ApiInfo> apis,
    List<DatabaseInfo> database,
    List<IntegrationInfo> integrations,
    List<ScheduledJobInfo> scheduledJobs,
    List<TechnicalDetail> technicalDetails,
    List<RoleInfo> roles,
    List<String> risks,
    List<String> followUpQuestions,
    List<SourceEvidence> sources,
    String workflowExample,
    DtoWorkflowDiagram workflowDiagram,
    boolean inScope
) {
    public DtoCodexKnowledgeResult {
        // Existing Advanced output does not request a Workflow example.
        if (workflowExample == null) workflowExample = "";
        if (workflowDiagram == null) workflowDiagram = DtoWorkflowDiagram.empty();
    }

    public record SourceEvidence(
        String repositoryName,
        String filePath,
        String symbol,
        int startLine,
        int endLine,
        String excerpt
    ) {}
}
