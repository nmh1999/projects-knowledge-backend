package com.projectsknowledge.dto;

import java.util.List;

public record KnowledgeAnswer(
        String project,
        String question,
        String summary,
        List<String> businessFlow,
        List<FlowNode> technicalFlow,
        List<ApiInfo> apis,
        List<DatabaseInfo> database,
        List<IntegrationInfo> integrations,
        List<ScheduledJobInfo> scheduledJobs,
        List<TechnicalDetail> technicalDetails,
        List<SourceReference> sources,
        String confidence,
        List<String> keyFindings,
        List<RoleInfo> roles,
        List<String> risks,
        List<String> followUpQuestions,
        boolean enoughEvidence,
        String workflowExample,
        WorkflowDiagram workflowDiagram,
        boolean inScope
) {
    public record FlowNode(String type, String name, String detail) {}
    public record ApiInfo(String method, String path, String controller, String methodName, String purpose) {}
    public record DatabaseInfo(String table, String entity, String repository, String purpose) {}
    public record IntegrationInfo(String name, String usedBy, String purpose) {}
    public record ScheduledJobInfo(String name, String purpose, String schedule) {}
    public record TechnicalDetail(String name, String type, String method, String responsibility) {}
    public record RoleInfo(String role, String capability, String evidence) {}
    public record SourceReference(String repositoryId, String repositoryName, String filePath,
                                  String fileName, String symbol, int startLine, int endLine, String excerpt) {}
}
