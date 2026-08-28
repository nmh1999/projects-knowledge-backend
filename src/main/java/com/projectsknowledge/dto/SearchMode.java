package com.projectsknowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SearchMode {
    @JsonProperty("basic") BASIC,
    @JsonProperty("advanced") ADVANCED,
    @JsonProperty("workflow") WORKFLOW
}
