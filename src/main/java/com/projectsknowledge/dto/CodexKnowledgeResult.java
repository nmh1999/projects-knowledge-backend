package com.projectsknowledge.dto;

import com.projectsknowledge.dto.KnowledgeAnswer.ApiInfo;
import com.projectsknowledge.dto.KnowledgeAnswer.DatabaseInfo;
import com.projectsknowledge.dto.KnowledgeAnswer.FlowNode;
import com.projectsknowledge.dto.KnowledgeAnswer.IntegrationInfo;
import com.projectsknowledge.dto.KnowledgeAnswer.RoleInfo;
import com.projectsknowledge.dto.KnowledgeAnswer.ScheduledJobInfo;
import com.projectsknowledge.dto.KnowledgeAnswer.TechnicalDetail;

import java.util.List;

public record CodexKnowledgeResult(
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
        WorkflowDiagram workflowDiagram,
        boolean inScope
) {
    public CodexKnowledgeResult {
        // Existing Advanced output does not request a Workflow example.
        if (workflowExample == null) workflowExample = "";
        if (workflowDiagram == null) workflowDiagram = WorkflowDiagram.empty();
    }
    public record SourceEvidence(String repositoryName, String filePath, String symbol,
                                 int startLine, int endLine, String excerpt) {}
}
